package com.nestra.remote.core.auth

import com.nestra.remote.core.http.HttpRequest
import com.nestra.remote.core.http.HttpTransport
import com.nestra.remote.core.http.setCookieValue
import com.nestra.remote.core.json.Json
import com.nestra.remote.core.json.bool
import com.nestra.remote.core.json.long
import com.nestra.remote.core.json.str
import java.io.IOException

/**
 * A NESTRA Parent session that Parent itself confirmed (GET /api/auth/me = 200 for [accountId]).
 * The value is a credential: kept in memory (and only Keystore-encrypted at rest by the app), never printed or logged,
 * sent ONLY to NESTRA Parent and, as "Authorization: Bearer", to the NESTRA Remote token endpoint.
 */
class ParentSession internal constructor(val accountId: Long, value: String, val origin: Origin, val parentTransport: Transport) {
    /** Where the session came from (BODY_TOKEN = login/MFA answer body, COOKIE_JAR = its Set-Cookie). */
    enum class Origin { BODY_TOKEN, COOKIE_JAR, RESTORED }
    /** How NESTRA Parent accepted it on /api/auth/me. NESTRA Remote always receives it as Bearer. */
    enum class Transport { BEARER, COOKIE }
    private var secret: CharArray? = value.toCharArray()
    internal val value: String get() = secret?.let { String(it) } ?: throw IllegalStateException("parent session wiped")
    val isWiped: Boolean get() = secret == null
    fun wipe() { secret?.fill('\u0000'); secret = null }
    override fun toString() = "ParentSession(account=$accountId, origin=$origin, ${if (isWiped) "wiped" else "present"})"

    companion object {
        /** Restores a session the app stored (Keystore-encrypted); it is re-proven with Parent before use. */
        fun restore(accountId: Long, value: String, transport: Transport): ParentSession = ParentSession(accountId, value, Origin.RESTORED, transport)
    }
}

/** Parent's MFA challenge after a correct password. NOT a session and never usable as a credential anywhere else. */
class MfaChallenge internal constructor(val accountId: Long, token: String, val expiresUtc: String?) {
    internal val token: String = token
    override fun toString() = "MfaChallenge(account=$accountId, expires=$expiresUtc)"
}

sealed class LoginResult {
    class Authenticated(val session: ParentSession) : LoginResult() { override fun toString() = "Authenticated($session)" }
    class MfaRequired(val challenge: MfaChallenge) : LoginResult() { override fun toString() = "MfaRequired($challenge)" }
    object InvalidCredentials : LoginResult()
    object MfaRejected : LoginResult()            // wrong / expired / reused code or challenge
    object MfaCodeMalformed : LoginResult()       // refused locally, nothing sent
    object NotEstablished : LoginResult()         // Parent answered 200 but /api/auth/me does not confirm a session
    object AccountMismatch : LoginResult()        // /api/auth/me confirms a DIFFERENT account than the login
    object RateLimited : LoginResult()
    class Failed(val reason: String) : LoginResult() { override fun toString() = "Failed($reason)" }
    object NetworkError : LoginResult()
}

/**
 * The live NESTRA Parent authentication contract (ETAP 7, proven in production):
 *   POST /api/auth/login {email, password}
 *     -> 200 {accountId, ..., mfaRequired:false, token}            (session; also Set-Cookie NestraParentSession)
 *     -> 200 {accountId, ..., mfaRequired:true, mfaChallengeToken, mfaChallengeExpiresUtc}   (NO session yet)
 *   POST /api/auth/mfa/verify-login {challengeToken, code}  -> 200 session (body token and/or Set-Cookie)
 *   GET  /api/auth/me   -> 200 {accountId, ...} only for an authenticated session (Bearer preferred by Parent)
 *   POST /api/auth/logout
 * A session counts only after /api/auth/me = 200 for the login's accountId. MFA is never bypassed or remembered:
 * the code is sent once to Parent and not kept.
 */
class ParentAuthClient(private val http: HttpTransport, parentBase: String = DEFAULT_BASE) {
    companion object {
        const val DEFAULT_BASE = "https://panel.nestraparent.com"
        const val COOKIE_NAME = "NestraParentSession"
        private val SIX_DIGITS = Regex("^[0-9]{6}$")
        /** Normalises what a person types ("123 456", "123-456") to six digits, or null. */
        fun normalizeMfaCode(input: String): String? = input.filter { !it.isWhitespace() && it != '-' }.takeIf { SIX_DIGITS.matches(it) }
    }
    private val base = parentBase.trimEnd('/')

    fun login(email: String, password: CharArray): LoginResult {
        if (email.isBlank() || password.isEmpty()) return LoginResult.InvalidCredentials
        val body = Json.write(linkedMapOf("email" to email.trim(), "password" to String(password)))
        val r = try { http.execute(HttpRequest("POST", "$base/api/auth/login", body = body)) } catch (e: IOException) { return LoginResult.NetworkError }
        return when (r.status) {
            200 -> {
                val j = parse(r.body) ?: return LoginResult.Failed("login answer is not JSON")
                val accountId = j.long("accountId")?.takeIf { it > 0 } ?: return LoginResult.Failed("login answer without accountId")
                if (j.bool("mfaRequired") == true) {
                    val ch = j.str("mfaChallengeToken")?.takeIf { it.isNotEmpty() } ?: return LoginResult.Failed("MFA required but no challenge")
                    LoginResult.MfaRequired(MfaChallenge(accountId, ch, j.str("mfaChallengeExpiresUtc")))
                } else establish(accountId, j.str("token"), r.setCookieValue(COOKIE_NAME), challenge = null)
            }
            400, 401, 404 -> LoginResult.InvalidCredentials
            429 -> LoginResult.RateLimited
            else -> LoginResult.Failed("login HTTP ${r.status}")
        }
    }

    /** Completes Parent's own MFA step. [code] is the 6-digit TOTP; malformed input is refused without any request. */
    fun verifyMfa(challenge: MfaChallenge, code: String): LoginResult {
        val c = normalizeMfaCode(code) ?: return LoginResult.MfaCodeMalformed
        val body = Json.write(linkedMapOf("challengeToken" to challenge.token, "code" to c))
        val r = try { http.execute(HttpRequest("POST", "$base/api/auth/mfa/verify-login", body = body)) } catch (e: IOException) { return LoginResult.NetworkError }
        return when (r.status) {
            200 -> {
                val j = parse(r.body) ?: return LoginResult.Failed("MFA answer is not JSON")
                if (j.bool("mfaRequired") == true) return LoginResult.MfaRejected
                establish(challenge.accountId, j.str("token"), r.setCookieValue(COOKIE_NAME), challenge)
            }
            400, 401, 403, 404, 410 -> LoginResult.MfaRejected
            429 -> LoginResult.RateLimited
            else -> LoginResult.Failed("MFA HTTP ${r.status}")
        }
    }

    /** Parent's own answer for a session: the account id, or null (401 / other account / unreachable). */
    fun me(session: ParentSession): MeResult = me(session.value, viaCookie = session.parentTransport == ParentSession.Transport.COOKIE)

    sealed class MeResult {
        class Ok(val accountId: Long) : MeResult()
        object Invalid : MeResult()
        object Unavailable : MeResult()
    }

    private fun me(value: String, viaCookie: Boolean): MeResult {
        val headers = if (viaCookie) mapOf("Cookie" to "$COOKIE_NAME=$value") else mapOf("Authorization" to "Bearer $value")
        val r = try { http.execute(HttpRequest("GET", "$base/api/auth/me", headers)) } catch (e: IOException) { return MeResult.Unavailable }
        return when (r.status) {
            200 -> parse(r.body)?.long("accountId")?.takeIf { it > 0 }?.let { MeResult.Ok(it) } ?: MeResult.Unavailable
            401, 403, 404 -> MeResult.Invalid
            else -> MeResult.Unavailable
        }
    }

    /** Ends the session at Parent (Bearer). Best effort: the local copy is wiped by the caller either way. */
    fun logout(session: ParentSession): Int = try {
        val h = if (session.parentTransport == ParentSession.Transport.COOKIE) mapOf("Cookie" to "$COOKIE_NAME=${session.value}") else mapOf("Authorization" to "Bearer ${session.value}")
        http.execute(HttpRequest("POST", "$base/api/auth/logout", h)).status
    } catch (e: Exception) { 0 }

    private fun establish(accountId: Long, bodyToken: String?, cookie: String?, challenge: MfaChallenge?): LoginResult {
        // a challenge is never a session, even if an answer echoed it back
        val candidates = listOfNotNull(
            bodyToken?.takeIf { it.isNotEmpty() && it != challenge?.token }?.let { it to ParentSession.Origin.BODY_TOKEN },
            cookie?.takeIf { it.isNotEmpty() && it != challenge?.token }?.let { it to ParentSession.Origin.COOKIE_JAR },
        )
        if (candidates.isEmpty()) return LoginResult.NotEstablished
        var mismatch = false
        for ((value, origin) in candidates) {
            // Bearer first (NESTRA Parent's ParentToken() prefers it); a cookie-jar session is also checked as cookie
            val viaBearer = me(value, viaCookie = false)
            val res = if (viaBearer is MeResult.Ok || origin == ParentSession.Origin.BODY_TOKEN) viaBearer else me(value, viaCookie = true)
            when (res) {
                is MeResult.Ok -> if (res.accountId == accountId) {
                    val t = if (res === viaBearer) ParentSession.Transport.BEARER else ParentSession.Transport.COOKIE
                    return LoginResult.Authenticated(ParentSession(accountId, value, origin, t))
                } else mismatch = true
                MeResult.Unavailable -> return LoginResult.NetworkError
                MeResult.Invalid -> {}
            }
        }
        return if (mismatch) LoginResult.AccountMismatch else LoginResult.NotEstablished
    }

    private fun parse(body: String): Map<String, Any?>? = try { Json.obj(body) } catch (e: Json.ParseException) { null }
}
