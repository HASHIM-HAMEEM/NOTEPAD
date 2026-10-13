package com.hashim.notepad.sync

import android.content.Context
import com.hashim.notepad.sync.SupabaseHttp.orThrow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class Session(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

private const val PENDING_CONFIRMATION_EMAIL = "pending_confirmation_email"

/** Supabase GoTrue email/password sessions, persisted so the app works offline and refreshes silently when online. */
class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("notepad.auth", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session
    val pendingConfirmationEmail: String? get() = prefs.getString(PENDING_CONFIRMATION_EMAIL, null)
    private val lock = Any()

    private fun load(): Session? {
        val uid = prefs.getString("uid", null) ?: return null
        return Session(uid, prefs.getString("email", "") ?: "", prefs.getString("access", "") ?: "", prefs.getString("refresh", "") ?: "", prefs.getLong("exp", 0))
    }

    private fun store(s: Session?) {
        prefs.edit().apply {
            if (s == null) clear() else {
                putString("uid", s.userId); putString("email", s.email); putString("access", s.accessToken)
                putString("refresh", s.refreshToken); putLong("exp", s.expiresAt)
            }
        }.apply()
        _session.value = s
    }

    private fun parse(json: String, fallbackEmail: String): Session {
        val o = JSONObject(json)
        val user = o.optJSONObject("user")
        val expiresIn = o.optLong("expires_in", 3600)
        return Session(
            userId = user?.optString("id") ?: "",
            email = user?.optString("email")?.takeIf { it.isNotEmpty() } ?: fallbackEmail,
            accessToken = o.getString("access_token"),
            refreshToken = o.optString("refresh_token"),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
    }

    private fun body(email: String, password: String) = JSONObject().put("email", email).put("password", password).toString()

    /** Returns null on success, or a user-facing message when email confirmation is pending. */
    fun signUp(email: String, password: String): String? {
        val cleanEmail = email.trim()
        val r = SupabaseHttp.json(
            "POST",
            "/auth/v1/signup?redirect_to=notepad%3A%2F%2Fauth-callback",
            null,
            body(cleanEmail, password),
        ).orThrow()
        val o = JSONObject(r.text)
        if (!o.has("access_token")) {
            prefs.edit().putString(PENDING_CONFIRMATION_EMAIL, cleanEmail).apply()
            return "Check your email and tap the link — it opens NotePad"
        }
        store(parse(r.text, cleanEmail))
        prefs.edit().remove(PENDING_CONFIRMATION_EMAIL).apply()
        return null
    }

    fun resendSignUpConfirmation(email: String) {
        val cleanEmail = email.trim()
        val json = JSONObject().put("type", "signup").put("email", cleanEmail).toString()
        SupabaseHttp.json(
            "POST",
            "/auth/v1/resend?redirect_to=notepad%3A%2F%2Fauth-callback",
            null,
            json,
        ).orThrow()
        prefs.edit().putString(PENDING_CONFIRMATION_EMAIL, cleanEmail).apply()
    }

    fun completeEmailConfirmation(accessToken: String, refreshToken: String, expiresIn: Long) {
        val user = JSONObject(SupabaseHttp.json("GET", "/auth/v1/user", accessToken).orThrow().text)
        val userId = user.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The confirmation link did not return a user")
        val email = user.optString("email").takeIf { it.isNotBlank() }
            ?: pendingConfirmationEmail.orEmpty()
        store(
            Session(
                userId = userId,
                email = email,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = System.currentTimeMillis() + expiresIn.coerceAtLeast(0) * 1000,
            ),
        )
        prefs.edit().remove(PENDING_CONFIRMATION_EMAIL).apply()
    }

    fun signIn(email: String, password: String) {
        val r = SupabaseHttp.json("POST", "/auth/v1/token?grant_type=password", null, body(email, password))
        if (!r.ok) {
            val msg = runCatching { JSONObject(r.text).let { it.optString("error_description").ifEmpty { it.optString("msg").ifEmpty { it.optString("message") } } } }.getOrNull()
            throw HttpException(r.code, msg?.ifEmpty { null } ?: r.text.take(200))
        }
        store(parse(r.text, email))
        prefs.edit().remove(PENDING_CONFIRMATION_EMAIL).apply()
    }

    fun signOut() {
        val s = _session.value
        store(null)
        if (s != null) runCatching { SupabaseHttp.json("POST", "/auth/v1/logout", s.accessToken) }
    }

    /** A valid access token, refreshing first when it expires within a minute. Throws when offline and expired. */
    fun token(): String {
        val s = _session.value ?: throw IllegalStateException("Not signed in")
        if (s.expiresAt - System.currentTimeMillis() > 60_000) return s.accessToken
        synchronized(lock) {
            val cur = _session.value ?: throw IllegalStateException("Not signed in")
            if (cur.expiresAt - System.currentTimeMillis() > 60_000) return cur.accessToken
            val r = SupabaseHttp.json("POST", "/auth/v1/token?grant_type=refresh_token", null, JSONObject().put("refresh_token", cur.refreshToken).toString())
            if (r.code in 400..499) { store(null); throw IllegalStateException("Session expired, sign in again") }
            r.orThrow()
            val next = parse(r.text, cur.email).let { if (it.userId.isEmpty()) it.copy(userId = cur.userId) else it }
            store(next)
            return next.accessToken
        }
    }
}
