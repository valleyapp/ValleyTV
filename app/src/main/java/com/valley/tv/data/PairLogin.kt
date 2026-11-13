package com.valley.tv.data

import com.valley.tv.core.Net
import org.json.JSONObject

/** QR sign-in: the TV asks the server for a short code, the phone signs in and the server hands the tokens back once */
object PairLogin {
    private const val SERVER = "https://valley-i8h5.onrender.com"

    /** a new code to show as a QR; null when the server cannot be reached */
    suspend fun start(): String? {
        val r = Net.postJson("$SERVER/api/pair/new", JSONObject())
        if (!r.ok) return null
        return r.json()?.optString("code")?.takeIf { it.isNotEmpty() }
    }

    /** the status JSON: "waiting", "done" (with refreshToken) or "expired"; null while the network is down */
    suspend fun poll(code: String): JSONObject? {
        val r = Net.get("$SERVER/api/pair/status?code=$code")
        return r.json()
    }
}
