package com.nestra.remote.core.api

import com.nestra.remote.core.auth.ParentSession
import com.nestra.remote.core.http.HttpRequest
import com.nestra.remote.core.http.HttpTransport
import com.nestra.remote.core.json.Json
import com.nestra.remote.core.json.bool
import com.nestra.remote.core.json.long
import com.nestra.remote.core.json.str
import com.nestra.remote.core.pairing.PairingInput
import java.io.IOException
import java.util.Base64

/** Outcome of every NESTRA Remote call. Errors carry no server text and no credential. */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    object Unauthorized : ApiResult<Nothing>()            // 401: token missing / expired / revoked / account blocked
    object NotFound : ApiResult<Nothing>()                // 404: not yours or does not exist (no existence oracle)
    object InvalidOrExpired : ApiResult<Nothing>()        // 400 pairing: wrong / expired / used code (deliberately one answer)
    object RateLimited : ApiResult<Nothing>()
    object SubscriptionExpired : ApiResult<Nothing>()     // 402 from the token endpoint
    object AccountNotActive : ApiResult<Nothing>()        // 403 from the token endpoint
    object ServiceUnavailable : ApiResult<Nothing>()
    // ETAP 9 session refusals (409 from POST /v1/account/devices/{id}/sessions)
    object RemoteAccessDisabled : ApiResult<Nothing>()    // switched OFF at the PC: only the person at the PC can enable it
    object DeviceOffline : ApiResult<Nothing>()
    object EngineNotReady : ApiResult<Nothing>()
    object SessionInProgress : ApiResult<Nothing>()
    data class Error(val status: Int, val code: String?) : ApiResult<Nothing>()
    object NetworkError : ApiResult<Nothing>()
}

/**
 * A short-lived NESTRA Remote account token (ES256 JWS from the NESTRA Account Bridge, 300 s). Memory only.
 * The client does NOT verify the signature (only NESTRA Remote can); it checks the metadata is what it asked for.
 */
class RemoteToken internal constructor(value: String, val accountId: Long, val expiresAtEpochSec: Long, val meta: TokenMeta) {
    private var secret: CharArray? = value.toCharArray()
    internal val value: String get() = secret?.let { String(it) } ?: throw IllegalStateException("remote token wiped")
    fun expiresWithin(seconds: Long, nowEpochSec: Long): Boolean = nowEpochSec + seconds >= expiresAtEpochSec
    fun wipe() { secret?.fill('\u0000'); secret = null }
    override fun toString() = "RemoteToken(account=$accountId, $meta)"
}

data class TokenMeta(val alg: String?, val kid: String?, val iss: String?, val aud: String?, val sub: String?,
                     val iat: Long?, val nbf: Long?, val exp: Long?) {
    val lifetimeSeconds: Long? get() = if (iat != null && exp != null) exp - iat else null
    override fun toString() = "alg=$alg kid=$kid iss=$iss aud=$aud sub=$sub lifetime=${lifetimeSeconds}s"
}

data class RemoteDevice(
    val deviceId: String, val name: String, val model: String?, val online: Boolean, val remoteEnabled: Boolean,
    val lastSeenUtc: String?, val pairedUtc: String?, val revoked: Boolean,
    val engineReady: Boolean = false, val liveSession: LiveSessionRef? = null,
) {
    /** Only the NESTRA Remote Windows agent exists in 0.1.x. */
    val platform: String get() = "Windows"
    val paired: Boolean get() = pairedUtc != null
    /** ETAP 9: Connect is offered only for a paired, online PC whose owner switched Remote Access ON at the PC. */
    val canConnect: Boolean get() = paired && online && remoteEnabled && !revoked
}

/** ETAP 9: a laptop's live session as the device list shows it (never a grant). */
data class LiveSessionRef(val sessionId: String, val state: String)

data class SessionStarted(val sessionId: String, val deviceId: String, val state: String, val expiresUtc: String?)

/**
 * ETAP 9 one-time connect data, handed out by the server exactly once. The grant is memory-only: [takeGrant] gives
 * the viewer a copy and wipes this one; it is never logged, written, put in a URL or shown.
 */
class SessionConnect internal constructor(val engineId: String, grant: CharArray, val rendezvousHost: String, val serverKey: String) {
    private var g: CharArray? = grant
    fun takeGrant(): CharArray { val c = g ?: throw IllegalStateException("grant already used"); val copy = c.copyOf(); wipe(); return copy }
    fun wipe() { g?.fill('\u0000'); g = null }
    override fun toString() = "SessionConnect(engine=$engineId, host=$rendezvousHost)"
}

data class SessionStatus(val sessionId: String, val deviceId: String, val state: String, val expiresUtc: String?,
                         val endReason: String?, val connect: SessionConnect?)

data class PairingPreview(val pairingId: String?, val deviceName: String, val model: String?, val expiresUtc: String?)
data class PairingDone(val deviceId: String, val deviceName: String, val paired: Boolean, val remoteEnabled: Boolean)

/**
 * NESTRA Remote API (https://remote.nestraparent.com). The account token travels ONLY as "Authorization: Bearer";
 * never in a URL, never as a cookie. The Parent session is sent only to /v1/auth/token (also as Bearer).
 */
class RemoteApi(private val http: HttpTransport, remoteBase: String = DEFAULT_BASE, authBase: String = remoteBase) {
    companion object {
        const val DEFAULT_BASE = "https://remote.nestraparent.com"
        const val AUDIENCE = "nestra-remote"
        /** ETAP 9: the ONLY rendezvous server and key a viewer may ever use (NESTRA's own hbbs/hbbr). */
        const val RENDEZVOUS_HOST = "remote.nestraparent.com"
        const val SERVER_KEY = "AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo="
        private val ID = Regex("^[0-9a-hjkmnp-tv-z]{26}$")
        private val ENGINE_ID = Regex("^[0-9]{6,12}$")
        private val GRANT = Regex("^[A-Za-z0-9_-]{43}$")
        private val STATES = setOf("requested", "accepted", "connecting", "active", "ending", "ended", "expired", "refused")
        fun isValidDeviceId(id: String): Boolean = ID.matches(id)
        fun isValidSessionId(id: String): Boolean = ID.matches(id)
    }
    private val base = remoteBase.trimEnd('/')
    private val auth = authBase.trimEnd('/')

    /** Exchanges a PROVEN Parent session for a Remote account token. */
    fun exchange(session: ParentSession, nowEpochSec: Long = System.currentTimeMillis() / 1000): ApiResult<RemoteToken> {
        val r = call("POST", "$auth/v1/auth/token", session.value) ?: return ApiResult.NetworkError
        return when (r.first) {
            200 -> {
                val j = r.second ?: return ApiResult.Error(200, "not_json")
                val token = j.str("token") ?: return ApiResult.Error(200, "no_token")
                val acc = j.long("accountId")
                val expiresIn = j.long("expiresIn") ?: 0
                if (j.str("tokenType") != "Bearer" || acc != session.accountId || expiresIn !in 1..600) return ApiResult.Error(200, "unexpected_token")
                val meta = decodeMeta(token) ?: return ApiResult.Error(200, "unreadable_token")
                if (meta.alg != "ES256" || meta.aud != AUDIENCE || meta.sub != acc.toString() || (meta.lifetimeSeconds ?: 0) !in 1..600)
                    return ApiResult.Error(200, "unexpected_token_claims")
                ApiResult.Ok(RemoteToken(token, acc, nowEpochSec + expiresIn, meta))
            }
            402 -> ApiResult.SubscriptionExpired
            403 -> ApiResult.AccountNotActive
            else -> common(r.first, r.second)
        }
    }

    fun whoami(t: RemoteToken): ApiResult<Long> = get("/v1/auth/whoami", t) { it.long("accountId") }

    fun devices(t: RemoteToken): ApiResult<List<RemoteDevice>> {
        val r = callRaw("GET", "$base/v1/account/devices", t.value) ?: return ApiResult.NetworkError
        if (r.first != 200) return common(r.first, parseObj(r.second))
        val list = try { Json.parse(r.second) as? List<*> } catch (e: Json.ParseException) { null } ?: return ApiResult.Error(200, "not_a_list")
        return ApiResult.Ok(list.mapNotNull { (it as? Map<*, *>)?.let { m -> device(@Suppress("UNCHECKED_CAST") (m as Map<String, Any?>)) } })
    }

    fun previewPairing(t: RemoteToken, input: PairingInput): ApiResult<PairingPreview> =
        post("/v1/pairing/preview", t, input.body()) { j ->
            j.str("deviceName")?.let { PairingPreview(j.str("pairingId"), it, j.str("model"), j.str("expiresUtc")) }
        }

    fun completePairing(t: RemoteToken, input: PairingInput): ApiResult<PairingDone> =
        post("/v1/pairing/complete", t, input.body()) { j ->
            j.str("deviceId")?.let { PairingDone(it, j.str("deviceName") ?: "", j.bool("paired") == true, j.bool("remoteEnabled") == true) }
        }

    fun unpair(t: RemoteToken, deviceId: String): ApiResult<Unit> {
        if (!isValidDeviceId(deviceId)) return ApiResult.NotFound
        return post("/v1/devices/$deviceId/unpair", t, null) { j -> if (j.bool("paired") == false) Unit else null }
    }

    // ------------------------------------------------------------------ ETAP 9 live sessions
    /** Asks for a session to an own, paired, online PC with Remote Access ON. Never switches Remote Access on. */
    fun startSession(t: RemoteToken, deviceId: String): ApiResult<SessionStarted> {
        if (!isValidDeviceId(deviceId)) return ApiResult.NotFound
        val r = call("POST", "$base/v1/account/devices/$deviceId/sessions", t.value) ?: return ApiResult.NetworkError
        if (r.first != 201) return common(r.first, r.second)
        val j = r.second ?: return ApiResult.Error(201, "not_json")
        val sid = j.str("sessionId")?.takeIf { isValidSessionId(it) } ?: return ApiResult.Error(201, "unexpected_answer")
        if (j.str("deviceId") != deviceId) return ApiResult.Error(201, "unexpected_answer")
        return ApiResult.Ok(SessionStarted(sid, deviceId, j.str("state") ?: "requested", j.str("expiresUtc")))
    }

    /** Session state; also the keepalive of an active session. Connect data is present at most once. */
    fun sessionStatus(t: RemoteToken, sessionId: String): ApiResult<SessionStatus> {
        if (!isValidSessionId(sessionId)) return ApiResult.NotFound
        val r = call("GET", "$base/v1/account/sessions/$sessionId", t.value) ?: return ApiResult.NetworkError
        if (r.first != 200) return common(r.first, r.second)
        val j = r.second ?: return ApiResult.Error(200, "not_json")
        val state = j.str("state")?.takeIf { it in STATES } ?: return ApiResult.Error(200, "unexpected_answer")
        if (j.str("sessionId") != sessionId) return ApiResult.Error(200, "unexpected_answer")
        var connect: SessionConnect? = null
        @Suppress("UNCHECKED_CAST") val c = j["connect"] as? Map<String, Any?>
        if (c != null) {
            val engine = c.str("engineId"); val grant = c.str("sessionGrant"); val host = c.str("rendezvousHost"); val key = c.str("serverKey")
            // a viewer is never pointed at another server or key, whatever an answer says
            if (engine == null || !ENGINE_ID.matches(engine) || grant == null || !GRANT.matches(grant) || host != RENDEZVOUS_HOST || key != SERVER_KEY)
                return ApiResult.Error(200, "unexpected_connect")
            connect = SessionConnect(engine, grant.toCharArray(), host, key)
        }
        return ApiResult.Ok(SessionStatus(sessionId, j.str("deviceId") ?: "", state, j.str("expiresUtc"), j.str("endReason"), connect))
    }

    /** Ends the session (idempotent on the server). */
    fun endSession(t: RemoteToken, sessionId: String): ApiResult<Unit> {
        if (!isValidSessionId(sessionId)) return ApiResult.NotFound
        return post("/v1/account/sessions/$sessionId/end", t, null) { j -> if (j.str("state") == "ended") Unit else null }
    }

    /** Revokes THIS token at NESTRA Remote (204). */
    fun logout(t: RemoteToken): ApiResult<Unit> {
        val r = callRaw("POST", "$base/v1/auth/logout", t.value) ?: return ApiResult.NetworkError
        return if (r.first == 204) ApiResult.Ok(Unit) else common(r.first, parseObj(r.second))
    }

    // ------------------------------------------------------------------ plumbing
    private fun <T> get(path: String, t: RemoteToken, map: (Map<String, Any?>) -> T?): ApiResult<T> {
        val r = call("GET", "$base$path", t.value) ?: return ApiResult.NetworkError
        return if (r.first == 200) r.second?.let(map)?.let { ApiResult.Ok(it) } ?: ApiResult.Error(200, "unexpected_answer") else common(r.first, r.second)
    }

    private fun <T> post(path: String, t: RemoteToken, body: String?, map: (Map<String, Any?>) -> T?): ApiResult<T> {
        val r = call("POST", "$base$path", t.value, body) ?: return ApiResult.NetworkError
        return if (r.first == 200) r.second?.let(map)?.let { ApiResult.Ok(it) } ?: ApiResult.Error(200, "unexpected_answer") else common(r.first, r.second)
    }

    private fun common(status: Int, j: Map<String, Any?>?): ApiResult<Nothing> = when (status) {
        401 -> ApiResult.Unauthorized
        404 -> ApiResult.NotFound
        400 -> if (j?.str("error") == "invalid_or_expired") ApiResult.InvalidOrExpired else ApiResult.Error(400, j?.str("error"))
        409 -> when (j?.str("error")) {
            "remote_access_disabled" -> ApiResult.RemoteAccessDisabled
            "device_offline" -> ApiResult.DeviceOffline
            "engine_not_ready" -> ApiResult.EngineNotReady
            "session_in_progress" -> ApiResult.SessionInProgress
            else -> ApiResult.Error(409, j?.str("error"))
        }
        429 -> ApiResult.RateLimited
        502, 503, 504 -> ApiResult.ServiceUnavailable
        else -> ApiResult.Error(status, j?.str("error"))
    }

    private fun call(method: String, url: String, bearer: String, body: String? = null): Pair<Int, Map<String, Any?>?>? =
        callRaw(method, url, bearer, body)?.let { it.first to parseObj(it.second) }

    private fun callRaw(method: String, url: String, bearer: String, body: String? = null): Pair<Int, String>? = try {
        val r = http.execute(HttpRequest(method, url, mapOf("Authorization" to "Bearer $bearer"), body))
        r.status to r.body
    } catch (e: IOException) { null }

    private fun parseObj(s: String): Map<String, Any?>? = try { Json.obj(s) } catch (e: Json.ParseException) { null }

    private fun device(m: Map<String, Any?>): RemoteDevice? {
        val id = m.str("deviceId")?.takeIf { isValidDeviceId(it) } ?: return null
        @Suppress("UNCHECKED_CAST") val ls = m["liveSession"] as? Map<String, Any?>
        val live = ls?.let { x -> x.str("sessionId")?.takeIf { isValidSessionId(it) }?.let { LiveSessionRef(it, x.str("state") ?: "") } }
        return RemoteDevice(id, m.str("name") ?: id, m.str("model"), m.bool("online") == true, m.bool("remoteEnabled") == true,
            m.str("lastSeenUtc"), m.str("pairedUtc"), m.bool("revoked") == true, m.bool("engineReady") == true, live)
    }

    private fun decodeMeta(jws: String): TokenMeta? = try {
        val parts = jws.split('.')
        if (parts.size != 3) null else {
            val d = Base64.getUrlDecoder()
            val h = Json.obj(String(d.decode(parts[0]), Charsets.UTF_8))
            val c = Json.obj(String(d.decode(parts[1]), Charsets.UTF_8))
            TokenMeta(h.str("alg"), h.str("kid"), c.str("iss"), c.str("aud"), c.str("sub"), c.long("iat"), c.long("nbf"), c.long("exp"))
        }
    } catch (e: Exception) { null }
}
