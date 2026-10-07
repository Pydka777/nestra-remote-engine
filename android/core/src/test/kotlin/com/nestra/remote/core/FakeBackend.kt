package com.nestra.remote.core

import com.nestra.remote.core.http.HttpRequest
import com.nestra.remote.core.http.HttpResponse
import com.nestra.remote.core.http.HttpTransport
import com.nestra.remote.core.json.Json
import com.nestra.remote.core.json.str
import java.util.Base64

/**
 * In-memory stand-in for NESTRA Parent + Bridge + Remote API, used ONLY by the fast unit tests. The real-backend gate is
 * android/integration (Kotlin core against the real NestraRemote.Server + AccountBridge + a Parent MFA contract host).
 */
class FakeBackend(var now: Long = 1_800_000_000L) : HttpTransport {
    class Account(val id: Long, val email: String, val password: String, val mfa: Boolean, val totp: String = "246810")
    val accounts = mutableMapOf<String, Account>()
    val sessions = mutableMapOf<String, Long>()              // parent session -> account
    val challenges = mutableMapOf<String, Pair<Long, Boolean>>() // challenge -> (account, used)
    val tokens = mutableMapOf<String, Pair<Long, Long>>()    // remote token -> (account, exp)
    val revoked = mutableSetOf<String>()
    val devices = mutableMapOf<String, MutableMap<String, Any?>>()
    val log = mutableListOf<HttpRequest>()
    var cookieOnly = false                                   // login/MFA answers carry the session only as Set-Cookie
    var echoChallenge = false
    var meAccountOverride: Long? = null
    var tokenAudience = "nestra-remote"
    private var n = 0

    fun add(a: Account) { accounts[a.email] = a }
    private fun next(p: String) = p + (++n).toString().padStart(40, 'X')
    private fun ok(j: Any?, vararg h: Pair<String, String>) = HttpResponse(200, h.groupBy({ it.first }, { it.second }), Json.write(j))
    private fun err(code: Int, e: String) = HttpResponse(code, emptyMap(), Json.write(mapOf("error" to e)))
    private fun bearer(r: HttpRequest) = r.headers["Authorization"]?.removePrefix("Bearer ")
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    fun session(acc: Account): HttpResponse {
        val s = next("PARENTSESSION")
        sessions[s] = acc.id
        val body = mutableMapOf<String, Any?>("accountId" to acc.id, "mfaRequired" to false)
        if (!cookieOnly) body["token"] = s
        return ok(body, "Set-Cookie" to "NestraParentSession=$s; path=/; secure; HttpOnly; samesite=lax")
    }

    fun mint(acc: Long, lifetime: Long = 300): String {
        val h = b64("""{"alg":"ES256","typ":"JWT","kid":"k1"}""")
        val c = b64("""{"iss":"https://remote.nestraparent.com/auth","aud":"$tokenAudience","sub":"$acc","iat":$now,"nbf":$now,"exp":${now + lifetime},"jti":"j${++n}"}""")
        val t = "$h.$c.${b64("sig$n")}"
        tokens[t] = acc to now + lifetime
        return t
    }

    @Synchronized override fun execute(request: HttpRequest): HttpResponse {
        log += request
        val path = request.url.substringAfter("://").substringAfter('/').let { "/$it" }
        val j = request.body?.let { Json.obj(it) } ?: emptyMap()
        return when (path) {
            "/api/auth/login" -> {
                val a = accounts[j.str("email")] ?: return err(401, "bad")
                if (a.password != j.str("password")) return err(401, "bad")
                if (!a.mfa) return session(a)
                val c = next("CHALLENGE"); challenges[c] = a.id to false
                ok(mapOf("accountId" to a.id, "mfaEnabled" to true, "mfaRequired" to true, "mfaChallengeToken" to c, "mfaChallengeExpiresUtc" to "2030-01-01T00:00:00Z"))
            }
            "/api/auth/mfa/verify-login" -> {
                val c = j.str("challengeToken") ?: return err(400, "bad")
                val (acc, used) = challenges[c] ?: return err(401, "bad")
                val a = accounts.values.first { it.id == acc }
                if (used || j.str("code") != a.totp) return err(401, "bad")
                challenges[c] = acc to true
                if (echoChallenge) return ok(mapOf("accountId" to acc, "token" to c, "mfaRequired" to false))
                session(a)
            }
            "/api/auth/me" -> {
                val s = bearer(request) ?: request.headers["Cookie"]?.removePrefix("NestraParentSession=")
                val acc = sessions[s] ?: return err(401, "unauthorized")
                ok(mapOf("accountId" to (meAccountOverride ?: acc)))
            }
            "/api/auth/logout" -> { sessions.remove(bearer(request)); ok(mapOf("ok" to true)) }
            "/v1/auth/token" -> {
                val acc = sessions[bearer(request)] ?: return err(401, "unauthorized")
                ok(mapOf("token" to mint(acc), "tokenType" to "Bearer", "expiresIn" to 300, "accountId" to acc))
            }
            else -> remote(path, request, j)
        }
    }

    private fun remote(path: String, r: HttpRequest, j: Map<String, Any?>): HttpResponse {
        val t = bearer(r)
        val (acc, exp) = tokens[t] ?: return err(401, "unauthorized")
        if (t in revoked || exp <= now) return err(401, "unauthorized")
        return when {
            path == "/v1/auth/whoami" -> ok(mapOf("accountId" to acc))
            path == "/v1/auth/logout" -> { revoked += t!!; HttpResponse(204, emptyMap(), "") }
            path == "/v1/account/devices" -> HttpResponse(200, emptyMap(), Json.write(devices.values.filter { it["owner"] == acc }.map { it - "owner" - "code" }))
            path == "/v1/pairing/preview" || path == "/v1/pairing/complete" -> {
                val d = devices.values.firstOrNull { it["code"] == j.str("code") && it["owner"] == null } ?: return err(400, "invalid_or_expired")
                if (path.endsWith("preview")) ok(mapOf("pairingId" to d["deviceId"], "deviceName" to d["name"], "model" to d["model"], "expiresUtc" to "x"))
                else { d["owner"] = acc; d["pairedUtc"] = "2026-10-07T00:00:00Z"; d.remove("code"); ok(mapOf("deviceId" to d["deviceId"], "deviceName" to d["name"], "paired" to true, "remoteEnabled" to false)) }
            }
            path.startsWith("/v1/devices/") && path.endsWith("/unpair") -> {
                val d = devices[path.removePrefix("/v1/devices/").removeSuffix("/unpair")]?.takeIf { it["owner"] == acc } ?: return err(404, "not_found")
                d["owner"] = null; d["pairedUtc"] = null
                ok(mapOf("deviceId" to d["deviceId"], "paired" to false, "remoteEnabled" to false))
            }
            path.startsWith("/v1/account/devices/") && path.endsWith("/sessions") && r.method == "POST" -> {
                val d = devices[path.removePrefix("/v1/account/devices/").removeSuffix("/sessions")]?.takeIf { it["owner"] == acc && it["pairedUtc"] != null }
                    ?: return err(404, "not_found")
                if (d["online"] != true) return err(409, "device_offline")
                if (d["remoteEnabled"] != true) return err(409, "remote_access_disabled")
                if (d["engineReady"] != true) return err(409, "engine_not_ready")
                if (rsessions.values.any { it["deviceId"] == d["deviceId"] && it["state"] in LIVE }) return err(409, "session_in_progress")
                val sid = "s" + (++n).toString().padStart(25, '0')
                val grant = "G" + (n.toString().padStart(42, 'g'))
                rsessions[sid] = mutableMapOf("sessionId" to sid, "deviceId" to d["deviceId"], "owner" to acc, "state" to "requested",
                    "grant" to grant, "delivered" to false, "endReason" to null)
                HttpResponse(201, emptyMap(), Json.write(mapOf("sessionId" to sid, "deviceId" to d["deviceId"], "state" to "requested", "expiresUtc" to "2030-01-01T00:00:00Z")))
            }
            path.startsWith("/v1/account/sessions/") && path.endsWith("/end") -> {
                val x = rsessions[path.removePrefix("/v1/account/sessions/").removeSuffix("/end")]?.takeIf { it["owner"] == acc } ?: return err(404, "not_found")
                ends += x["sessionId"] as String
                if (x["state"] in LIVE) { x["state"] = "ended"; x["endReason"] = "viewer_disconnect" }
                ok(mapOf("sessionId" to x["sessionId"], "state" to "ended"))
            }
            path.startsWith("/v1/account/sessions/") -> {
                val x = rsessions[path.removePrefix("/v1/account/sessions/")]?.takeIf { it["owner"] == acc } ?: return err(404, "not_found")
                statusReads++
                var connect: Map<String, Any?>? = null
                if (x["state"] == "accepted" && x["delivered"] == false) {
                    x["state"] = "connecting"; x["delivered"] = true
                    connect = mapOf("engineId" to "123456789", "sessionGrant" to x["grant"], "rendezvousHost" to connectHost, "serverKey" to connectKey)
                }
                ok(mapOf("sessionId" to x["sessionId"], "deviceId" to x["deviceId"], "state" to x["state"], "expiresUtc" to "2030-01-01T00:00:00Z",
                    "endReason" to x["endReason"], "connect" to connect))
            }
            else -> err(404, "not_found")
        }
    }

    // ---- ETAP 9 live sessions: the laptop's side is driven by the test
    val rsessions = mutableMapOf<String, MutableMap<String, Any?>>()
    val ends = mutableListOf<String>()
    var statusReads = 0
    var connectHost = "remote.nestraparent.com"
    var connectKey = "AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo="
    private val LIVE = setOf("requested", "accepted", "connecting", "active", "ending")
    @Synchronized fun pc(sid: String, state: String, reason: String? = null) { rsessions.getValue(sid)["state"] = state; rsessions.getValue(sid)["endReason"] = reason }
    @Synchronized fun sessionState(sid: String): String? = rsessions[sid]?.get("state") as String?
    @Synchronized fun grantOf(sid: String): String = rsessions.getValue(sid)["grant"] as String
    @Synchronized fun onlySession(): String? = rsessions.keys.singleOrNull()

    fun laptop(id: String, code: String) {
        devices[id] = mutableMapOf("deviceId" to id, "name" to "RAFAL-LAPTOP", "model" to "ThinkPad", "online" to true, "remoteEnabled" to false,
            "lastSeenUtc" to "2026-10-07T10:00:00Z", "pairedUtc" to null, "revoked" to false, "owner" to null, "code" to code,
            "engineReady" to true, "liveSession" to null)
    }
}
