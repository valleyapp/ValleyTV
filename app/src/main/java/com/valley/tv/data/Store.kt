package com.valley.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Immutable
import com.valley.tv.core.I18n
import com.valley.tv.core.Net
import com.valley.tv.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class User(
    val uid: String,
    val email: String,
    val name: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

enum class SyncStatus { GUEST, SYNCED, PENDING, ERROR }

/** read-only view of the synced blob for the UI (re-built after every change) */
@Immutable
data class Snapshot(
    val lib: List<MetaItem> = emptyList(),
    val cw: List<CwItem> = emptyList(),
    val fav: List<MetaItem> = emptyList(),
    val follow: List<MetaItem> = emptyList(),
    val watched: Set<String> = emptySet(),
    val eps: Map<String, EpState> = emptyMap(),
    val ads: List<Addon> = emptyList(),
    val settings: Settings = Settings(),
) {
    /** activeCW() in app.js: titles explicitly marked watched never show up again */
    val continueWatching: List<CwItem> get() = cw.filter { it.item.id.isNotEmpty() && it.item.id !in watched }.take(24)
    val enabledAddons: List<Addon> get() = ads.filter { it.on }
    fun inLib(id: String) = lib.any { it.id == id }
    fun isFav(id: String) = fav.any { it.id == id }
    fun progressOf(id: String): Int = continueWatching.firstOrNull { it.item.id == id }?.pr ?: 0
}

/**
 * The account state: one JSON blob, same keys as the Windows app (lib, cw, fav, follow, watched, ads, eps, lastSrc, settings).
 *
 * Rules (same spirit as cloud-sync.js, plus a real merge):
 *  - nothing is ever written before this session has read the cloud copy once (a fresh install can't wipe the account)
 *  - writes are debounced (3 s normally, 30 s while a video plays) - frequent writes slow a weak TV down
 *  - every write re-reads the document and merges (three-way) so changes from other devices are kept
 *  - writes are conditional on the document's updateTime; if another device wrote in between, re-read + merge + retry
 *  - the 1-hour ID token is refreshed with the refresh token before it expires (and on 401/403)
 * Trakt, player options, recent searches etc. stay local only (never part of the blob).
 */
object Store {
    private const val TAG = "VALLEY_SYNC"
    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val netMutex = Mutex()

    private var data: JSONObject = Merge.empty()
    private var base: JSONObject? = null        // cloud copy last seen by this device
    private var changeSeq = 0L
    private var pushedSeq = 0L
    private var ready = false                   // cloud read at least once in this session
    private var lastPull = 0L
    /** time of the last successful read/write with the cloud (for Settings › Account) */
    val lastSyncAt = MutableStateFlow(0L)
    private var pushJob: Job? = null
    private var retryJob: Job? = null
    private var saveJob: Job? = null
    private var defAddonBusy = false

    val user = MutableStateFlow<User?>(null)
    val guest = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val snap = MutableStateFlow(Snapshot())
    val status = MutableStateFlow(SyncStatus.GUEST)
    /** the account's cloud copy was read at least once in this session (profiles are known) */
    val cloudReady = MutableStateFlow(false)

    /** true while the player is open: cloud writes wait longer */
    var playing = false

    /**
     * What the account carries: addons, the TMDB / MDBList keys, the library and continue watching (+ watched / episodes).
     * Everything else in "settings" (language, look, subtitles, playback...) belongs to this device only:
     * kept in [dev], never pushed, never replaced by another device.
     */
    private val SYNCED_SETTINGS: Set<String> get() = Settings.TYPES.keys
    private var dev: JSONObject = JSONObject()
    private fun saveDev() = prefs.edit().putString("dev_settings", dev.toString()).apply()

    private val dataFile get() = File(appCtx.filesDir, "sync_data.json")
    private val baseFile get() = File(appCtx.filesDir, "sync_base.json")

    /* ------------------------------------------------------------------ start-up */

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("valley", Context.MODE_PRIVATE)
        user.value = loadUser()
        guest.value = prefs.getBoolean("guest", false)
        status.value = if (user.value != null) SyncStatus.PENDING else SyncStatus.GUEST
        scope.launch {
            val (d, b) = withContext(Dispatchers.IO) {
                val d = try { if (dataFile.exists()) JSONObject(dataFile.readText()) else null } catch (e: Exception) { null }
                val b = try { if (baseFile.exists()) JSONObject(baseFile.readText()) else null } catch (e: Exception) { null }
                d to b
            }
            if (d != null) data = Merge.sanitize(d)
            base = b?.let { Merge.sanitize(it) }
            if (user.value == null && guest.value && Merge.migrateProfilesToAccount(data)) scheduleLocalSave()
            // device settings: first start after this update keeps what the TV already had
            val saved = prefs.getString("dev_settings", null)
            dev = try { if (saved != null) JSONObject(saved) else null } catch (e: Exception) { null } ?: JSONObject().also { o ->
                val cur = data.optJSONObject("settings") ?: JSONObject()
                cur.keys().forEach { k -> if (k !in SYNCED_SETTINGS) o.put(k, cur.get(k)) }
                prefs.edit().putString("dev_settings", o.toString()).apply()
            }
            // settings now live in the account (per profile): bring this device's old local values along once
            if (!prefs.getBoolean("settings_cloud_v1", false)) {
                prefs.edit().putBoolean("settings_cloud_v1", true).apply()
                if (dev.length() > 0) {
                    val st = data.optJSONObject("settings") ?: JSONObject().also { data.put("settings", it) }
                    dev.keys().forEach { k -> if (!st.has(k) && Settings.TYPES.containsKey(k)) st.put(k, dev.get(k)) }
                    scheduleLocalSave()
                }
            }
            ensureCinemeta()
            publish()
            loaded.value = true
            if (user.value != null) pull() else if (guest.value) ensureDefaultAddon()
        }
        // keep the TV up to date with Windows without "Sync now": read the account every 90 s
        // while the app is open (not during playback, not in the background)
        scope.launch {
            while (true) {
                delay(90_000)
                if (user.value != null && foreground && !playing && System.currentTimeMillis() - lastPull > 80_000) netMutex.withLock { pullInternal() }
            }
        }
    }

    /** true while the app is on screen (MainActivity onStart / onStop) */
    @Volatile var foreground = true

    private fun loadUser(): User? {
        val uid = prefs.getString("uid", null) ?: return null
        val storedId = prefs.getString("idToken", "") ?: ""
        val storedRefresh = prefs.getString("refreshToken", "") ?: ""
        val tokens = try {
            SessionCrypto.decrypt(storedId) to SessionCrypto.decrypt(storedRefresh)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to unlock saved session", e)
            return null
        }
        if ((storedId.isNotEmpty() && !SessionCrypto.isEncrypted(storedId)) ||
            (storedRefresh.isNotEmpty() && !SessionCrypto.isEncrypted(storedRefresh))) {
            try {
                val encryptedId = SessionCrypto.encrypt(tokens.first)
                val encryptedRefresh = SessionCrypto.encrypt(tokens.second)
                if (!prefs.edit().putString("idToken", encryptedId).putString("refreshToken", encryptedRefresh).commit())
                    Log.e(TAG, "Unable to commit encrypted session migration")
            } catch (e: Exception) { Log.e(TAG, "Unable to encrypt existing session", e) }
        }
        return User(
            uid = uid, email = prefs.getString("email", "") ?: "", name = prefs.getString("name", "") ?: "",
            idToken = tokens.first, refreshToken = tokens.second,
            expiresAt = prefs.getLong("expiresAt", 0L),
        )
    }

    private fun saveUser() {
        val u = user.value
        val e = prefs.edit()
        if (u == null) {
            e.remove("uid").remove("email").remove("name").remove("idToken").remove("refreshToken").remove("expiresAt")
        } else {
            val tokens = try { SessionCrypto.encrypt(u.idToken) to SessionCrypto.encrypt(u.refreshToken) }
            catch (ex: Exception) { Log.e(TAG, "Unable to protect session; it will not be saved", ex); return }
            e.putString("uid", u.uid).putString("email", u.email).putString("name", u.name)
                .putString("idToken", tokens.first).putString("refreshToken", tokens.second).putLong("expiresAt", u.expiresAt)
        }
        e.putBoolean("guest", guest.value).apply()
    }

    /* ------------------------------------------------------------------ auth */

    /** returns null on success, otherwise a translation key for the error */
    suspend fun signIn(email: String, password: String): String? {
        val r = FirebaseApi.signIn(email, password)
        if (!r.ok) {
            val m = r.error
            return when {
                m == "NETWORK" -> "eNet"
                m.contains("EMAIL_NOT_FOUND") -> "eNoAcc"
                m.contains("INVALID_LOGIN_CREDENTIALS") || m.contains("INVALID_PASSWORD") -> "eWrong"
                m.contains("TOO_MANY_ATTEMPTS") -> "eMany"
                m.contains("INVALID_EMAIL") -> "eEmail"
                else -> "eWrong"
            }
        }
        startSession(r, r.name.ifEmpty { r.email.substringBefore('@') })
        return null
    }

    /** signs in with a refresh token the phone handed over (QR sign-in) */
    suspend fun signInWithRefresh(refreshToken: String): Boolean {
        if (refreshToken.isEmpty()) return false
        val r = FirebaseApi.refresh(refreshToken)
        if (!r.ok) return false
        startSession(r, r.name.ifEmpty { "VALLEY" })
        return true
    }

    /** language chosen on the login screen: kept after the account's cloud settings arrive */
    var loginLang: String? = null

    private suspend fun startSession(r: FirebaseApi.AuthResult, name: String) {
        base = null; ready = false; cloudReady.value = false
        user.value = User(r.uid, r.email, name, r.idToken, r.refreshToken, System.currentTimeMillis() + r.expiresInSec * 1000)
        guest.value = false
        saveUser()
        status.value = SyncStatus.PENDING
        // Do not hold the login screen open while Firestore is read. A device with a local profile
        // cache can enter immediately; a fresh install waits on ProfileGate only until the first
        // account read completes, while the network request runs independently.
        val chosenLang = loginLang
        loginLang = null
        scope.launch {
            var ok = netMutex.withLock { pullInternal() }
            var retries = 0
            while (!ok && !cloudReady.value && retries++ < 3 && user.value != null) {
                delay(1_200)
                ok = netMutex.withLock { pullInternal() }
            }
            if (ok && chosenLang != null && Settings.from(settingsObj(cont())).lang != chosenLang) {
                setSetting("lang", chosenLang)
            }
        }
    }

    /** create account (accounts:signUp + display name), then the same start as sign-in */
    suspend fun signUp(name: String, email: String, password: String): String? {
        val r = FirebaseApi.signUp(email, password)
        if (!r.ok) {
            val m = r.error
            return when {
                m == "NETWORK" -> "eNet"
                m.contains("EMAIL_EXISTS") -> "eExists"
                m.contains("WEAK_PASSWORD") -> "ePass"
                m.contains("INVALID_EMAIL") -> "eEmail"
                m.contains("TOO_MANY_ATTEMPTS") -> "eMany"
                else -> "eNet"
            }
        }
        val nm = name.trim().take(40)
        FirebaseApi.updateName(r.idToken, nm)
        startSession(r, nm)
        return null
    }

    suspend fun updateName(name: String): Boolean {
        val token = validToken() ?: return false
        val nm = name.trim().take(40)
        if (nm.isEmpty() || !FirebaseApi.updateName(token, nm)) return false
        user.value = user.value?.copy(name = nm); saveUser()
        return true
    }

    /** delete the account: re-authenticate with the password, delete users/{uid}, delete the Firebase user */
    suspend fun deleteAccount(password: String): String? {
        val u = user.value ?: return "deleteFailed"
        val r = FirebaseApi.signIn(u.email, password)
        if (!r.ok) return if (r.error == "NETWORK") "eNet" else "eWrong"
        if (!FirebaseApi.deleteDoc(u.uid, r.idToken)) return "deleteFailed"
        if (!FirebaseApi.deleteAccount(r.idToken)) return "deleteFailed"
        pushJob?.cancel(); retryJob?.cancel()
        changeSeq = 0; pushedSeq = 0
        user.value = null; guest.value = false; saveUser(); ProfileStore.reset(); cloudReady.value = false
        data = Merge.empty(); base = null; ready = false
        ensureCinemeta()
        withContext(Dispatchers.IO) { dataFile.delete(); baseFile.delete() }
        status.value = SyncStatus.GUEST
        publish()
        return null
    }

    fun continueAsGuest() {
        guest.value = true; saveUser(); status.value = SyncStatus.GUEST
        ensureDefaultAddon()
    }

    /** back from the guest profile screen to the sign-in page (keeps nothing to wipe: a guest has no account) */
    fun leaveGuest() { guest.value = false; saveUser(); ProfileStore.reset() }

    fun signOut() {
        scope.launch {
            if (user.value != null && changeSeq != pushedSeq) pushNow()   // don't lose the last changes
            pushJob?.cancel(); retryJob?.cancel()
            user.value = null; guest.value = false; saveUser(); ProfileStore.reset(); cloudReady.value = false
            // the Trakt link belongs to the account that made it: signing out ends it too
            com.valley.tv.data.Trakt.disconnect()
            data = Merge.empty(); base = null; ready = false; changeSeq = 0; pushedSeq = 0
            ensureCinemeta()
            withContext(Dispatchers.IO) { dataFile.delete(); baseFile.delete() }
            status.value = SyncStatus.GUEST
            publish()
        }
    }

    private suspend fun validToken(): String? {
        val u = user.value ?: return null
        if (u.idToken.isNotEmpty() && u.expiresAt - System.currentTimeMillis() > 5 * 60_000) return u.idToken
        return if (refreshToken()) user.value?.idToken else null
    }

    private suspend fun refreshToken(): Boolean {
        val u = user.value ?: return false
        if (u.refreshToken.isEmpty()) return false
        val r = FirebaseApi.refresh(u.refreshToken)
        if (!r.ok) { Log.w(TAG, "token refresh failed: ${r.error}"); return false }
        user.value = u.copy(idToken = r.idToken, refreshToken = r.refreshToken, expiresAt = System.currentTimeMillis() + r.expiresInSec * 1000)
        saveUser()
        return true
    }

    /* ------------------------------------------------------------------ cloud */

    private fun parseBlob(blob: String?): JSONObject? = try { if (blob.isNullOrEmpty()) null else Merge.sanitize(JSONObject(blob)) } catch (e: Exception) { null }

    fun pull() { scope.launch { netMutex.withLock { pullInternal() } } }

    /** app came back to the foreground: pick up progress made on Windows meanwhile */
    fun onForeground() {
        foreground = true
        if (user.value != null && !playing && System.currentTimeMillis() - lastPull > 20_000) pull()
    }

    /** app goes to the background: send pending changes now */
    fun flush() {
        foreground = false
        if (user.value != null && changeSeq != pushedSeq) { pushJob?.cancel(); scope.launch { pushNow() } }
        // written on this thread, not fire-and-forget: a TV with little RAM can kill the process moments after
        // the screen turns off (onStop), before a background coroutine gets to run - which used to lose the
        // last local save and briefly show "create your first profile" again on the next cold start
        saveLocalBlocking()
    }

    private suspend fun pullInternal(): Boolean {
        val u = user.value ?: return false
        var attempt = 0
        while (attempt++ < 2) {
            val token = validToken() ?: run { status.value = SyncStatus.ERROR; return false }
            val doc = FirebaseApi.getDoc(u.uid, token)
            if (doc.authError) { if (refreshToken()) continue else { status.value = SyncStatus.ERROR; return false } }
            if (!doc.ok) { status.value = SyncStatus.ERROR; scheduleRetry(); return false }
            val remote = parseBlob(doc.blob)
            if (remote == null) {
                // first sign-in on this account: the document is created from what this device has
                changeSeq++
            } else {
                val b = base
                data = if (b == null) remote.copy() else Merge.sanitize(Merge.merge(b, data, remote))
                base = remote
                if (data.toString() != remote.toString()) changeSeq++ else pushedSeq = changeSeq
            }
            if (Merge.migrateProfilesToAccount(data)) changeSeq++
            ready = true
            lastPull = System.currentTimeMillis()
            lastSyncAt.value = lastPull
            ensureCinemeta()
            publish(); cloudReady.value = true; scheduleLocalSave()
            status.value = if (changeSeq == pushedSeq) SyncStatus.SYNCED else SyncStatus.PENDING
            if (changeSeq != pushedSeq) schedulePush(1_000)
            ensureDefaultAddon()
            return true
        }
        return false
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        // the very first pull after sign-in / a cold start can fail simply because the TV's network isn't
        // fully up yet (just woke up / just booted) - retry that one quickly instead of waiting the full 30s,
        // so "who's watching" doesn't sit on the loading screen far longer than the network hiccup itself
        val delayMs = if (!cloudReady.value) 1_200L else 30_000L
        retryJob = scope.launch { delay(delayMs); if (ready) pushNow() else netMutex.withLock { pullInternal() } }
    }

    private fun schedulePush(delayMs: Long? = null) {
        if (user.value == null) return
        status.value = SyncStatus.PENDING
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs ?: if (playing) 30_000 else 3_000)
            scope.launch { pushNow() }   // separate job: a new debounce never cancels a write in flight
        }
    }

    /** forces an immediate sync (Settings › Sync now) */
    fun syncNow() { scope.launch { if (!ready) netMutex.withLock { pullInternal() }; if (changeSeq != pushedSeq) pushNow() else netMutex.withLock { pullInternal() } } }

    private suspend fun pushNow(): Boolean = netMutex.withLock {
        val u = user.value ?: return@withLock false
        if (!ready && !pullInternal()) return@withLock false
        if (changeSeq == pushedSeq) { status.value = SyncStatus.SYNCED; return@withLock true }
        var attempt = 0
        while (attempt++ < 3) {
            val token = validToken() ?: break
            val doc = FirebaseApi.getDoc(u.uid, token)
            if (doc.authError) { if (refreshToken()) continue else break }
            if (!doc.ok) { scheduleRetry(); break }
            val remote = parseBlob(doc.blob)
            val seqAtStart = changeSeq
            val localCopy = data.copy()
            val merged = Merge.sanitize(if (remote == null) localCopy else Merge.merge(base ?: remote, localCopy, remote))
            val json = Merge.fit(merged)
            if (json == null) { Log.w(TAG, "blob too large, not written"); break }
            val w = FirebaseApi.setDoc(u.uid, token, json, doc.updateTime, !doc.exists)
            if (w.authError) { if (refreshToken()) continue else break }
            if (w.conflict) { Log.i(TAG, "another device wrote meanwhile, merging again"); continue }
            if (!w.ok) { scheduleRetry(); break }
            val written = JSONObject(json)
            // changes made while the request was in flight are re-applied on top of what was written
            data = if (changeSeq == seqAtStart) written.copy() else Merge.sanitize(Merge.merge(localCopy, data, written))
            base = written
            pushedSeq = seqAtStart
            lastPull = System.currentTimeMillis()
            lastSyncAt.value = lastPull
            publish(); scheduleLocalSave()
            if (changeSeq != pushedSeq) schedulePush()
            status.value = if (changeSeq == pushedSeq) SyncStatus.SYNCED else SyncStatus.PENDING
            return@withLock true
        }
        status.value = SyncStatus.ERROR
        false
    }

    /* ------------------------------------------------------------------ local persistence */

    private fun scheduleLocalSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(1_500); saveLocalNow() }
    }

    private fun saveLocalNow() {
        if (!::appCtx.isInitialized) return
        val d = data.toString()
        val b = base?.toString()
        scope.launch(Dispatchers.IO) { writeLocal(d, b) }
    }

    /** same write as [saveLocalNow], but done before returning (small JSON, a few ms) so it survives a process
     *  kill right after onStop - used when the app is about to go to the background, never on the main loop */
    private fun saveLocalBlocking() {
        if (!::appCtx.isInitialized) return
        saveJob?.cancel()
        val d = data.toString()
        val b = base?.toString()
        try { kotlinx.coroutines.runBlocking(Dispatchers.IO) { writeLocal(d, b) } } catch (e: Exception) { Log.w(TAG, "local save failed", e) }
    }

    private fun writeLocal(d: String, b: String?) {
        try {
            File(appCtx.filesDir, "sync_data.tmp").apply { writeText(d); renameTo(dataFile) }
            if (b != null) File(appCtx.filesDir, "sync_base.tmp").apply { writeText(b); renameTo(baseFile) } else baseFile.delete()
        } catch (e: Exception) { Log.w(TAG, "local save failed", e) }
    }

    /* ------------------------------------------------------------------ mutations */

    /** every change goes through here (main thread). [pushDelayMs] shortens the usual debounce for changes that
     *  should reach the account quickly (adding a profile, picking its avatar) instead of waiting out the normal
     *  few seconds meant for things like continuous playback progress */
    private fun mutate(push: Boolean = true, root: Boolean = false, pushDelayMs: Long? = null, block: (JSONObject) -> Unit) {
        block(if (root) data else cont())
        changeSeq++
        publish()
        scheduleLocalSave()
        if (push && user.value != null) schedulePush(pushDelayMs)
    }

    /** the data of the profile that is open now: the top level for the main profile, `pf.d[id]` for the others */
    private fun cont(d: JSONObject = data): JSONObject {
        return d
    }

    private fun pfOf(d: JSONObject): JSONObject =
        d.optJSONObject("pf") ?: JSONObject().put("main", "").put("list", JSONArray()).put("d", JSONObject()).also { d.put("pf", it) }

    fun addProfile(name: String, avatar: Int): Profile {
        val id = System.currentTimeMillis().toString(36)
        var made: Profile? = null
        mutate(root = true, pushDelayMs = 200) { d ->
            val p = pfOf(d)
            val list = p.optJSONArray("list") ?: JSONArray().also { p.put("list", it) }
            val color = if (avatar >= 0) avatar % PROFILE_COLORS.size else list.length() % PROFILE_COLORS.size
            val nm = name.trim().take(20)
            list.put(JSONObject().put("id", id).put("name", nm).put("c", color))
            val first = p.optString("main").isEmpty()
            if (first) p.put("main", id)   // the account's existing data (if any) becomes this profile's
            else (p.optJSONObject("d") ?: JSONObject().also { p.put("d", it) }).put(id, Merge.emptyContainer().also { ensureCinemetaIn(it) })
            made = Profile(id, nm, color, first)
        }
        return made!!
    }

    fun removeProfile(id: String) {
        if (ProfileStore.profiles.value.firstOrNull { it.id == id }?.main == true) return
        if (ProfileStore.active.value?.id == id) ProfileStore.active.value = null
        mutate(root = true, pushDelayMs = 200) { d ->
            val p = d.optJSONObject("pf") ?: return@mutate
            p.put("list", (p.optJSONArray("list") ?: JSONArray()).removeWhere { it.str("id") == id })
            p.optJSONObject("d")?.remove(id)
        }
    }

    /** picture avatar for one profile (Settings › Account › Edit profile); -1 clears it back to colour + initial */
    fun setProfileAvatar(id: String, avatarIdx: Int) {
        mutate(root = true, pushDelayMs = 200) { d ->
            val list = d.optJSONObject("pf")?.optJSONArray("list") ?: return@mutate
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.str("id") == id) { if (avatarIdx >= 0) o.put("im", avatarIdx) else o.remove("im"); break }
            }
        }
    }

    fun renameProfile(id: String, name: String) {
        val nm = name.trim().take(20); if (nm.isEmpty()) return
        mutate(root = true, pushDelayMs = 200) { d ->
            val list = d.optJSONObject("pf")?.optJSONArray("list") ?: return@mutate
            for (i in 0 until list.length()) { val o = list.optJSONObject(i) ?: continue; if (o.str("id") == id) { o.put("name", nm); break } }
        }
    }

    private fun lastKey() = "last_profile_" + (user.value?.uid ?: "guest")
    /** the profile opened last ON THIS DEVICE for the current account (null = ask "who's watching").
     *  Device-only on purpose: forgotten on sign-out and on reinstall, never taken from the cloud. */
    fun lastProfileId(): String? = try { prefs.getString(lastKey(), null) } catch (e: Exception) { null }

    fun saveLastProfile(id: String) {
        try { prefs.edit().putString(lastKey(), id).apply() } catch (e: Exception) {}
    }

    /** sign-out / leaving guest mode: this device forgets which profile it had open */
    fun clearLastProfiles() {
        try {
            val e = prefs.edit()
            prefs.all.keys.filter { it.startsWith("last_profile_") }.forEach { e.remove(it) }
            e.apply()
        } catch (e: Exception) {}
    }

    /* ---------------- PIN lock (4 digits, per profile, stored hashed inside the profile's list item `pn`) ---------------- */

    private fun pinHash(id: String, pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(("valley.pin:$id:$pin").toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun verifyPin(id: String, pin: String): Boolean {
        val list = data.optJSONObject("pf")?.optJSONArray("list") ?: return true
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.str("id") == id) { val h = o.str("pn"); return h.isEmpty() || h == pinHash(id, pin) }
        }
        return true
    }

    /** [pin] = 4 digits to lock the profile, null to remove the lock */
    fun setProfilePin(id: String, pin: String?) {
        mutate(root = true, pushDelayMs = 200) { d ->
            val list = d.optJSONObject("pf")?.optJSONArray("list") ?: return@mutate
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.str("id") == id) { if (pin != null && pin.length == 4) o.put("pn", pinHash(id, pin)) else o.remove("pn"); break }
            }
        }
    }

    /** a profile was opened: show its own library / addons */
    fun profileChanged() {
        ensureCinemeta()
        publish()
        ensureDefaultAddon()
    }

    private fun publish() {
        // profiles of the account (the list is part of the synced document)
        val pfo = data.optJSONObject("pf")
        val mainId = pfo?.optString("main") ?: ""
        val plist = pfo?.optJSONArray("list").objects().mapNotNull { o ->
            val pid = o.str("id"); if (pid.isEmpty()) null else Profile(pid, o.str("name").ifEmpty { "?" }, o.optInt("c", 0), pid == mainId, o.optInt("im", -1), o.str("pn").isNotEmpty())
        }
        ProfileStore.profiles.value = plist
        // remembered locally (device-only) so a later cold start never mistakes "not loaded yet" for "no profiles"
        if (plist.isNotEmpty()) Prefs.noteProfilesSeen()
        ProfileStore.active.value?.let { a ->
            val na = plist.firstOrNull { it.id == a.id }
            if (na != a) ProfileStore.active.value = na   // removed on another device -> back to the picker
        }
        val d = cont()
        val s = Settings.from(settingsObj(d))
        I18n.lang = when (s.lang) { "en" -> "en"; "ar" -> "ar"; else -> if (java.util.Locale.getDefault().language == "ar") "ar" else "en" }
        com.valley.tv.ui.theme.GlassLevel.fromBlur(s.blur)
        val eps = LinkedHashMap<String, EpState>()   // insertion order = oldest first, like the JS object
        d.optJSONObject("eps")?.let { o ->
            o.keys().forEach { k ->
                val e = o.optJSONObject(k) ?: return@forEach
                eps[k] = EpState(e.optInt("pr", 0), e.optDouble("ts", 0.0).let { if (it.isNaN()) 0.0 else it },
                    when (val dn = e.opt("done")) { is Boolean -> dn; is Number -> dn.toInt() != 0; else -> false })
            }
        }
        snap.value = Snapshot(
            lib = d.optJSONArray("lib").objects().map { MetaItem.fromSlim(it) }.filter { it.id.isNotEmpty() }.distinctBy { it.id },
            cw = d.optJSONArray("cw").objects().map { CwItem.from(it) }.distinctBy { it.item.id },
            fav = d.optJSONArray("fav").objects().map { MetaItem.fromSlim(it) }.filter { it.id.isNotEmpty() }.distinctBy { it.id },
            follow = d.optJSONArray("follow").objects().map { MetaItem.fromSlim(it) }.filter { it.id.isNotEmpty() }.distinctBy { it.id },
            watched = d.optJSONArray("watched").strings().toHashSet(),
            eps = eps,
            ads = d.optJSONArray("ads").objects().mapNotNull { Addon.from(it) }.distinctBy { it.base },
            settings = s,
        )
    }

    private fun arr(d: JSONObject, k: String): JSONArray = d.optJSONArray(k) ?: JSONArray().also { d.put(k, it) }
    private fun obj(d: JSONObject, k: String): JSONObject = d.optJSONObject(k) ?: JSONObject().also { d.put(k, it) }

    private fun JSONArray.removeWhere(pred: (JSONObject) -> Boolean): JSONArray {
        val out = JSONArray()
        for (i in 0 until length()) { val v = opt(i); if (!(v is JSONObject && pred(v))) out.put(v) }
        return out
    }

    private fun JSONArray.unshift(v: Any): JSONArray {
        val out = JSONArray(); out.put(v); for (i in 0 until length()) out.put(opt(i)); return out
    }

    fun toggleLib(item: MetaItem): Boolean {
        var added = false
        mutate { d ->
            val lib = arr(d, "lib")
            val has = (0 until lib.length()).any { lib.optJSONObject(it)?.str("id") == item.id }
            added = !has
            d.put("lib", if (has) lib.removeWhere { it.str("id") == item.id } else lib.unshift(item.toSlim().put("added", System.currentTimeMillis())))
        }
        return added
    }

    fun toggleFav(item: MetaItem): Boolean {
        var added = false
        mutate { d ->
            val fav = arr(d, "fav")
            val has = (0 until fav.length()).any { fav.optJSONObject(it)?.str("id") == item.id }
            added = !has
            d.put("fav", if (has) fav.removeWhere { it.str("id") == item.id } else fav.unshift(item.toSlim()))
        }
        return added
    }

    private fun epSet(d: JSONObject, vid: String, st: JSONObject?) {
        if (vid.isEmpty()) return
        val eps = obj(d, "eps")
        eps.remove(vid)
        if (st != null) eps.put(vid, st)
        if (eps.length() > 800) {
            val ks = ArrayList<String>(); eps.keys().forEach { ks.add(it) }
            ks.take(eps.length() - 800).forEach { eps.remove(it) }
        }
    }

    private fun doneState() = JSONObject().put("pr", 100).put("ts", 0).put("done", 1)

    /** markWatched() from app.js: title (and all its known episodes) watched / unwatched */
    fun toggleWatched(item: MetaItem, videos: List<Video>): Boolean {
        var nowOn = false
        mutate { d ->
            val w = arr(d, "watched")
            val list = w.strings().toMutableList()
            val on = list.contains(item.id)
            if (on) list.remove(item.id) else list.add(item.id)
            nowOn = !on
            d.put("watched", JSONArray(list))
            d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id })
            videos.forEach { v -> epSet(d, v.id, if (on) null else doneState()) }
            obj(d, "settings").put("resumeX", "")
        }
        return nowOn
    }

    fun setEpisodeWatched(item: MetaItem, vid: String, done: Boolean) = mutate { d ->
        epSet(d, vid, if (done) doneState() else null)
        d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id && it.str("vid") == vid })
    }

    /** season menu: every episode of a season watched / unwatched */
    fun setSeasonWatched(item: MetaItem, episodes: List<Video>, done: Boolean) = mutate { d ->
        episodes.forEach { v -> epSet(d, v.id, if (done) doneState() else null) }
        val ids = episodes.map { it.id }.toHashSet()
        d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id && it.str("vid") in ids })
    }

    /** Settings › Data › export: the whole account document as text */
    fun exportBackup(): String = data.toString()

    /** Settings › Data › import: replaces the account document with a backup made by [exportBackup] */
    fun importBackup(txt: String): Boolean {
        val j = try { JSONObject(txt) } catch (e: Exception) { return false }
        if (!j.has("lib") && !j.has("ads") && !j.has("cw")) return false
        data = Merge.sanitize(j)
        changeSeq++; publish(); scheduleLocalSave()
        if (user.value != null) schedulePush(null)
        return true
    }

    /** Settings › Data › delete lists and addons (synced: applies to every device) */
    fun clearAll() = mutate { d ->
        listOf("lib", "cw", "fav", "follow", "watched").forEach { d.put(it, JSONArray()) }
        d.put("eps", JSONObject()); d.put("lastSrc", JSONObject()); d.put("ads", JSONArray())
        ensureCinemetaIn(d)
    }

    /** start over: clears the progress of every episode (like epAct('restart')) or the movie */
    fun clearProgress(item: MetaItem, videos: List<Video>) = mutate { d ->
        videos.filter { it.season > 0 }.forEach { epSet(d, it.id, null) }
        d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id })
    }

    /** saveProgress() from app.js (called every 10 s while playing and when the player closes) */
    fun saveProgress(item: MetaItem, vid: String, season: Int?, episode: Int?, posSec: Double, durSec: Double) {
        if (durSec <= 0 || posSec.isNaN()) return
        val pr = (posSec / durSec * 100).roundToInt().coerceIn(0, 100)
        if (pr < 1) return
        mutate { d ->
            if (arr(d, "watched").strings().contains(item.id)) {
                d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id }); return@mutate
            }
            if (season != null) epSet(d, vid, if (pr >= 92) doneState() else JSONObject().put("pr", pr).put("ts", posSec).put("done", 0))
            var cw = arr(d, "cw").removeWhere { it.str("id") == item.id }
            val finished = if (season != null) pr >= 92 else pr >= 95
            if (!finished) {
                val e = item.toSlim().put("pr", pr).put("ts", posSec).put("vid", vid)
                    .put("season", season ?: JSONObject.NULL).put("episode", episode ?: JSONObject.NULL)
                    .put("type", if (season != null) "series" else item.type)
                cw = cw.unshift(e)
                val capped = JSONArray(); for (i in 0 until minOf(24, cw.length())) capped.put(cw.opt(i)); cw = capped
                obj(d, "settings").put("resumeX", "")
            }
            d.put("cw", cw)
        }
    }

    /** where to resume: the continue-watching entry for that episode, or the episode's own progress */
    fun resumePosition(itemId: String, vid: String): Double {
        val s = snap.value
        val c = s.cw.firstOrNull { it.item.id == itemId && it.vid == vid }
        if (c != null && c.ts > 5) return c.ts
        val e = s.eps[vid]
        return if (e != null && !e.done && e.ts > 5) e.ts else 0.0
    }

    /** account settings (keys / addons) + this device's own settings on top */
    private fun settingsObj(d: JSONObject): JSONObject {
        val out = JSONObject()
        d.optJSONObject("settings")?.let { c -> c.keys().forEach { k -> if (k in SYNCED_SETTINGS) out.put(k, c.get(k)) } }
        dev.keys().forEach { k -> if (k !in SYNCED_SETTINGS) out.put(k, dev.get(k)) }
        return out
    }

    fun setSetting(key: String, value: Any) {
        val type = Settings.TYPES[key] ?: return
        if (key !in SYNCED_SETTINGS) {
            if (type == 'n') dev.put(key, (value as? Number)?.toInt() ?: 0) else dev.put(key, value.toString())
            saveDev(); publish()
            return
        }
        mutate { d ->
            val s = obj(d, "settings")
            if (type == 'n') s.put(key, (value as? Number)?.toInt() ?: 0) else s.put(key, value.toString())
        }
    }

    /* ---------------- last source (srcRemember) ---------------- */

    fun rememberSource(itemId: String, vid: String, s: StreamItem) = mutate { d ->
        val last = obj(d, "lastSrc")
        last.remove(itemId)
        last.put(itemId, JSONObject().put("base", s.addon.base).put("nm", s.name.lowercase().take(160))
            .put("txt", s.text.take(400)).put("url", s.url.take(1500)).put("vid", vid).put("ts", System.currentTimeMillis()))
        if (last.length() > 150) {
            val ks = ArrayList<String>(); last.keys().forEach { ks.add(it) }
            ks.take(last.length() - 150).forEach { last.remove(it) }
        }
    }

    fun lastSource(itemId: String): JSONObject? = cont().optJSONObject("lastSrc")?.optJSONObject(itemId)

    /* ---------------- addons ---------------- */

    private fun ensureCinemeta() { Merge.containers(data).forEach { ensureCinemetaIn(it) } }

    private fun ensureCinemetaIn(data: JSONObject) {
        // Cinemeta is no longer part of the app: drop it, and make sure the VALLEY catalog is installed first
        val raw = data.optJSONArray("ads") ?: JSONArray()
        val kept = JSONArray()
        var hasValley = false
        for (i in 0 until raw.length()) {
            val a = raw.optJSONObject(i) ?: continue
            val base = a.str("base")
            if (base.contains("cinemeta", true) || a.str("id").contains("cinemeta", true)) continue
            if (base == Catalog.VALLEY) { hasValley = true; if (a.optJSONObject("mf") == null) a.put("mf", Catalog.fallbackManifest()) }
            kept.put(a)
        }
        val out = JSONArray()
        if (!hasValley) out.put(JSONObject().put("base", Catalog.VALLEY).put("id", "me.valley.catalog")
            .put("on", 1).put("builtin", 1).put("mf", Catalog.fallbackManifest()))
        for (i in 0 until kept.length()) out.put(kept.get(i))
        data.put("ads", out)
    }

    fun setAddonOn(base: String, on: Boolean) = mutate { d ->
        val ads = arr(d, "ads")
        for (i in 0 until ads.length()) { val a = ads.optJSONObject(i) ?: continue; if (a.str("base") == base) a.put("on", if (on) 1 else 0) }
    }

    fun moveAddon(base: String, dir: Int) = mutate { d ->
        val list = arr(d, "ads").objects().toMutableList()
        val i = list.indexOfFirst { it.str("base") == base }; val j = i + dir
        if (i >= 0 && j in list.indices) { val x = list[i]; list[i] = list[j]; list[j] = x }
        d.put("ads", JSONArray().also { a -> list.forEach { a.put(it) } })
    }

    fun removeAddon(base: String) = mutate { d ->
        d.put("ads", arr(d, "ads").removeWhere { it.str("base") == base && !(it.opt("builtin") == 1 || it.opt("builtin") == true) })
        if (base == Catalog.DEF_ADDON) obj(d, "settings").put("defAds", "removed")
        Net.forget(base)
    }

    fun addAddon(base: String, mf: JSONObject) = mutate { d ->
        val ads = arr(d, "ads").removeWhere { it.str("base") == base }
        ads.put(JSONObject().put("base", base).put("id", mf.str("id")).put("on", 1).put("mf", mf))
        d.put("ads", ads)
        Net.forget(base)
    }

    /** ensureDefaultAddon() from app.js: OpenSubtitles for everyone unless the user removed it on purpose */
    private fun ensureDefaultAddon() {
        if (defAddonBusy) return
        if (user.value != null && !ready) return
        val s = snap.value
        if (s.settings.defAds == "removed" || s.ads.any { it.base == Catalog.DEF_ADDON }) return
        defAddonBusy = true
        scope.launch {
            try {
                val mf = Net.getJson(Catalog.DEF_ADDON + "/manifest.json", useCache = false)
                if (mf != null && mf.str("id").isNotEmpty() && mf.optJSONArray("resources") != null &&
                    snap.value.settings.defAds != "removed" && snap.value.ads.none { it.base == Catalog.DEF_ADDON }) {
                    mutate { d ->
                        arr(d, "ads").put(JSONObject().put("base", Catalog.DEF_ADDON).put("id", mf.str("id")).put("on", 1).put("mf", mf))
                        obj(d, "settings").put("defAds", "added")
                    }
                }
            } finally { defAddonBusy = false }
        }
    }

    /** seasonSetWatched() from app.js: every episode of one season watched / unwatched */
    fun setSeasonWatched(item: MetaItem, videos: List<Video>, season: Int, done: Boolean) = mutate { d ->
        val ids = videos.filter { it.season == season }.map { it.id }.toHashSet()
        ids.forEach { epSet(d, it, if (done) doneState() else null) }
        d.put("cw", arr(d, "cw").removeWhere { it.str("id") == item.id && it.str("vid") in ids })
    }

    /** refreshAllAddons() from app.js: re-reads every addon manifest; returns (updated, failed) */
    suspend fun refreshAddons(): Pair<Int, Int> {
        var ok = 0; var bad = 0
        val fresh = HashMap<String, JSONObject>()
        snap.value.ads.forEach { a ->
            val mf = Net.getJson(a.base.trimEnd('/') + "/manifest.json", useCache = false)
            if (mf != null && mf.str("id").isNotEmpty() && mf.str("name").isNotEmpty() && mf.optJSONArray("resources") != null) {
                fresh[a.base] = mf; ok++; Net.forget(a.base)
            } else bad++
        }
        if (fresh.isNotEmpty()) mutate { d ->
            val ads = arr(d, "ads")
            for (i in 0 until ads.length()) {
                val a = ads.optJSONObject(i) ?: continue
                fresh[a.str("base")]?.let { mf -> a.put("mf", mf); a.put("id", mf.str("id")) }
            }
        }
        return ok to bad
    }

    /** «مسح بياناتي» (clearT in app.js): library, progress and addons - also on the account, like Windows */
    fun clearMyData() = mutate { d ->
        listOf("lib", "cw", "fav", "follow", "watched").forEach { d.put(it, JSONArray()) }
        d.put("eps", JSONObject()); d.put("lastSrc", JSONObject())
        d.put("ads", JSONArray())
        ensureCinemetaIn(d)
    }

    /* ---------------- local-only bits (never synced) ---------------- */

    fun recentSearches(): List<String> = prefs.getString("sh", "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberSearch(q: String) {
        val v = q.trim(); if (v.length < 2) return
        val l = (listOf(v) + recentSearches().filter { !it.equals(v, ignoreCase = true) }).take(10)
        prefs.edit().putString("sh", l.joinToString("\n")).apply()
    }

    fun removeSearch(q: String) {
        prefs.edit().putString("sh", recentSearches().filter { it != q }.joinToString("\n")).apply()
    }
    fun clearSearches() { prefs.edit().remove("sh").apply() }

    /** original-language cache (S.ol in app.js), local only */
    fun origLang(key: String): String? = prefs.getString("ol:$key", null)
    fun setOrigLang(key: String, code: String) { prefs.edit().putString("ol:$key", code).apply() }
}
