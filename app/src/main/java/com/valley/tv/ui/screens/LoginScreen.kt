package com.valley.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.valley.tv.ui.components.AuthBackdrop
import com.valley.tv.ui.components.AuthLogo
import com.valley.tv.ui.components.AuthButton
import com.valley.tv.ui.components.WelcomeTitle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.valley.tv.ui.components.Icon
import com.valley.tv.ui.theme.LocalAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valley.tv.R
import com.valley.tv.core.I18n
import com.valley.tv.core.t
import com.valley.tv.data.Catalog
import com.valley.tv.data.FirebaseApi
import com.valley.tv.data.Row as CatRow
import com.valley.tv.data.Store
import com.valley.tv.data.PairLogin
import com.valley.tv.ui.Toasts
import com.valley.tv.ui.components.Focusable
import com.valley.tv.ui.components.Ic
import com.valley.tv.ui.components.NetImage
import com.valley.tv.ui.components.PillButton
import com.valley.tv.ui.components.Txt
import com.valley.tv.ui.components.QrImage
import com.valley.tv.ui.components.VField
import com.valley.tv.ui.components.glass
import com.valley.tv.ui.components.mirrorInRtl
import com.valley.tv.ui.components.rememberFieldCtl
import com.valley.tv.ui.theme.VC
import com.valley.tv.ui.theme.ts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Mode { WELCOME, IN, CREATE, FORGOT }

/** loginHTML() for TV: same card as Windows (sign in / create account / forgot / guest) next to a poster wall */
@Composable
fun LoginScreen() {
    var mode by remember { mutableStateOf(Mode.WELCOME) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nameCtl = rememberFieldCtl()
    val emailCtl = rememberFieldCtl()
    val passCtl = rememberFieldCtl()
    val emailRe = remember { Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") }

    // first focus: the first field (the keyboard stays closed until OK)
    LaunchedEffect(mode) { delay(80); if (mode == Mode.CREATE) nameCtl.focus() else if (mode != Mode.WELCOME) emailCtl.focus() }
    // after the keyboard closes on the last field the focus goes to the main button (never to a link further down)
    val ctaFr = remember { FocusRequester() }
    var focusCta by remember { mutableStateOf(0) }
    LaunchedEffect(focusCta) { if (focusCta > 0) { delay(90); try { ctaFr.requestFocus() } catch (_: Exception) {} } }

    fun submit() {
        val e = email.trim().lowercase()
        err = ""; ok = ""
        if (mode == Mode.CREATE && name.isBlank()) { err = t("eName"); return }
        if (!emailRe.matches(e)) { err = t("eEmail"); return }
        if (mode == Mode.FORGOT) {
            busy = true
            scope.launch {
                val r = FirebaseApi.sendPasswordReset(e)
                busy = false
                if (r == null) { ok = t("resetSent") } else { err = t(if (r.contains("EMAIL_NOT_FOUND")) "eNoAcc" else "eNet") }
            }
            return
        }
        if (pass.length < 8) { err = t("ePass"); return }
        busy = true
        scope.launch {
            val r = if (mode == Mode.CREATE) Store.signUp(name, e, pass) else Store.signIn(e, pass)
            busy = false
            if (r != null) { err = t(r); focusCta++ } else { Toasts.show(t("welcomeT", Store.user.value?.name ?: "")) }
        }
    }

    fun switchMode(m: Mode) { mode = m; err = ""; ok = "" }

    AuthBackdrop {
        if (busy && mode != Mode.FORGOT) {
            com.valley.tv.ui.components.BrandLoader(Modifier.align(Alignment.Center))
            return@AuthBackdrop
        }
        if (mode == Mode.WELCOME) {
            Welcome(onCreate = { switchMode(Mode.CREATE) }, onSignIn = { switchMode(Mode.IN) })
        } else {
            AuthLogo(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp))
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalLayoutDirection provides (if (com.valley.tv.core.I18n.ar) LayoutDirection.Rtl else LayoutDirection.Ltr)) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().glass(RoundedCornerShape(30.dp), VC.cardC.copy(alpha = .92f))
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // language button (English / العربية) - changes the whole page right away
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Focusable(onClick = {
                        val nl = if (I18n.ar) "en" else "ar"
                        Store.loginLang = nl
                        Store.setSetting("lang", nl)
                    }, shape = RoundedCornerShape(999.dp), container = VC.g, focusedContainer = VC.g2) { _ ->
                        Txt(t("otherLang"), ts(13.sp, FontWeight.SemiBold), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                Txt(if (mode == Mode.FORGOT) t("forgotT") else t("welcome"), ts(26.sp, FontWeight.SemiBold), Modifier.fillMaxWidth())
                Txt(if (mode == Mode.FORGOT) t("sendReset") else t("lsub2"), ts(13.sp, color = VC.mu), Modifier.fillMaxWidth(), maxLines = 2)

                if (mode == Mode.CREATE) {
                    VField(name, { name = it }, t("name"), icon = Ic.user, imeAction = ImeAction.Next,
                        onIme = { emailCtl.editing = true }, ctl = nameCtl)
                }
                VField(email, { email = it }, t("email"), icon = Ic.mail, keyboardType = KeyboardType.Email,
                    imeAction = if (mode == Mode.FORGOT) ImeAction.Done else ImeAction.Next,
                    onIme = { if (mode == Mode.FORGOT) { focusCta++; submit() } else { passCtl.editing = true } }, ctl = emailCtl, ltr = true)
                if (mode != Mode.FORGOT) {
                    VField(pass, { pass = it }, t("pass"), icon = Ic.lock, password = true, imeAction = ImeAction.Done,
                        onIme = { focusCta++; submit() }, ctl = passCtl, ltr = true)
                }
                if (mode == Mode.IN) {
                    Row(Modifier.fillMaxWidth()) {
                        Focusable(onClick = { switchMode(Mode.FORGOT) }, shape = RoundedCornerShape(10.dp), focusedContainer = VC.g2) { _ ->
                            Txt(t("forgot"), ts(13.sp, FontWeight.SemiBold), Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                if (err.isNotEmpty()) Txt(err, ts(13.sp, color = VC.er), Modifier.fillMaxWidth(), maxLines = 2)
                if (ok.isNotEmpty()) Txt(ok, ts(13.sp, color = VC.watched), Modifier.fillMaxWidth(), maxLines = 2)

                AuthButton(
                    text = when { busy && mode == Mode.CREATE -> t("creating"); busy -> t("signingIn"); mode == Mode.CREATE -> t("createBtn"); mode == Mode.FORGOT -> t("sendReset"); else -> t("signin") },
                    onClick = { if (!busy) submit() }, modifier = Modifier.fillMaxWidth(), height = 52.dp, focusRequester = ctaFr,
                )

                // "Don't have an account? Create account" / "Already have an account? Sign in" / back from forgot
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    when (mode) {
                        Mode.IN -> { Txt(t("noAcc"), ts(13.sp, color = VC.mu)); Spacer(Modifier.width(6.dp)); LinkBtn(t("create")) { switchMode(Mode.CREATE) } }
                        Mode.CREATE -> { Txt(t("haveAcc"), ts(13.sp, color = VC.mu)); Spacer(Modifier.width(6.dp)); LinkBtn(t("signin")) { switchMode(Mode.IN) } }
                        Mode.FORGOT -> LinkBtn(t("backLogin")) { switchMode(Mode.IN) }
                        Mode.WELCOME -> Unit
                    }
                }
                Txt(t("terms0") + "\n" + t("terms") + " · " + t("privacy"), ts(12.sp, color = VC.mu, lineHeight = 18.sp),
                    Modifier.fillMaxWidth(), maxLines = 3, align = TextAlign.Center)
                if (mode == Mode.CREATE) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        LinkBtn(t("guest")) { Store.continueAsGuest() }
                    }
                }
            }
            }
            }
            BackHandler { switchMode(Mode.WELCOME) }
        }
    }
}

/** landing page: poster wall behind, logo top-left, title, create account / sign in, page dots */
@Composable
private fun Welcome(onCreate: () -> Unit, onSignIn: () -> Unit) {
    val ac = LocalAccent.current
    // phone sign-in: the server gives a code, the phone signs in on /pair, and the TV receives the session
    var pairing by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairErr by remember { mutableStateOf(false) }
    LaunchedEffect(pairing) {
        if (!pairing) return@LaunchedEffect
        pairCode = ""; pairErr = false
        val code = PairLogin.start()
        if (code == null) { pairErr = true; return@LaunchedEffect }
        pairCode = code
        val end = System.currentTimeMillis() + 10 * 60_000L
        while (pairing && System.currentTimeMillis() < end) {
            delay(3000)
            val j = PairLogin.poll(code) ?: continue
            when (j.optString("status")) {
                "done" -> {
                    if (Store.signInWithRefresh(j.optString("refreshToken"))) Toasts.show(t("welcomeT", Store.user.value?.name ?: ""))
                    else pairErr = true
                    return@LaunchedEffect
                }
                "expired" -> { pairErr = true; return@LaunchedEffect }
            }
        }
        if (pairing) pairErr = true
    }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); try { firstFr.requestFocus() } catch (_: Exception) {} }
    Box(Modifier.fillMaxSize()) {
        PosterWall(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.62f))
        AuthLogo(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp))
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WelcomeTitle(46.sp)
            Spacer(Modifier.height(10.dp))
            Txt(t("slogan2"), ts(17.sp, color = VC.mu), align = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            if (pairing) {
                // TEST: the QR and the code replace the two big buttons so everything fits on the TV screen
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (pairCode.isNotEmpty()) QrImage("https://valley-i8h5.onrender.com/pair?c=$pairCode", Modifier.size(170.dp)) else Spacer(Modifier.size(170.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(if (I18n.ar) "امسح الرمز بجوالك" else "Scan with your phone", ts(22.sp, FontWeight.SemiBold), Modifier.fillMaxWidth(), align = TextAlign.Center)
                        Txt(pairCode, ts(36.sp, FontWeight.Bold), Modifier.fillMaxWidth(), align = TextAlign.Center)
                        if (pairErr) Txt(if (I18n.ar) "تعذّر إنشاء الرمز أو انتهى، ارجع وجرب مرة ثانية" else "Could not create the code or it expired, go back and try again", ts(14.sp, color = VC.er), Modifier.fillMaxWidth(), align = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        OutlineBtn(if (I18n.ar) "رجوع" else "Back", null, { pairing = false }, Modifier.fillMaxWidth().height(52.dp))
                    }
                }
            } else {
                AuthButton(t("create"), onClick = onCreate, icon = Ic.userAdd, modifier = Modifier.fillMaxWidth(), height = 56.dp, focusRequester = firstFr)
                Spacer(Modifier.height(14.dp))
                OutlineBtn(t("signin"), Ic.login, onSignIn, Modifier.fillMaxWidth().height(56.dp))
                Spacer(Modifier.height(10.dp))
                OutlineBtn(if (I18n.ar) "تسجيل الدخول بالجوال" else "Sign in with your phone", Ic.login, { pairing = true }, Modifier.fillMaxWidth().height(56.dp))
            }
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(ac.ac1, CircleShape))
                Box(Modifier.size(8.dp).background(VC.g2, CircleShape))
                Box(Modifier.size(8.dp).background(VC.g2, CircleShape))
            }
        }
    }
}

@Composable
fun OutlineBtn(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(999.dp), container = Color.Transparent,
        focusedContainer = Color(0x1AFFFFFF), focusScale = 1.04f) { _ ->
        Box(Modifier.fillMaxSize().border(1.dp, VC.gb, RoundedCornerShape(999.dp)), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, VC.tx, 20.dp); Spacer(Modifier.width(8.dp)) }
                Txt(text, ts(15.sp, FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun LinkBtn(text: String, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(10.dp), focusedContainer = VC.g2) { _ ->
        Txt(text, ts(13.sp, FontWeight.SemiBold), Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** wall of trending posters (cached catalog, so it's instant after the first start) with the VALLEY logo */
@Composable
private fun PosterWall(modifier: Modifier) {
    var posters by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val row = CatRow("wall", "top", "movie", Catalog.CINEMETA, "${Catalog.CINEMETA}/catalog/movie/top.json", "")
        posters = Catalog.loadList(row).map { it.poster }.filter { it.isNotEmpty() }.take(16)
    }
    Box(modifier.clip(RoundedCornerShape(0.dp))) {
        if (posters.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.Center).requiredSize(1000.dp, 900.dp).graphicsLayer { rotationZ = -8f },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (c in 0 until 5) {
                    Column(Modifier.offset(y = if (c % 2 == 0) (-60).dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (r in 0 until 3) {
                            val p = posters.getOrNull((c * 3 + r) % posters.size) ?: continue
                            Box(Modifier.size(150.dp, 225.dp).clip(RoundedCornerShape(14.dp)).background(VC.card)) {
                                NetImage(p, 150.dp, 225.dp, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        // shade (.lws) so the logo stands out and the wall fades into the page
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(VC.loginA.copy(alpha = .15f), VC.loginB.copy(alpha = .55f)), radius = 1100f)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(VC.loginB, VC.loginB.copy(alpha = .55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(VC.loginB.copy(alpha = .6f), Color.Transparent, VC.loginB.copy(alpha = .8f)))))
    }
}
