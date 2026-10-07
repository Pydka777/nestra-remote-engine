package com.nestra.remote.it

import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.auth.ParentAuthClient
import com.nestra.remote.core.http.HttpRequest
import com.nestra.remote.core.http.HttpResponse
import com.nestra.remote.core.http.HttpTransport
import com.nestra.remote.core.http.UrlConnectionTransport
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.core.security.MemorySecretStore
import com.nestra.remote.core.session.LiveSessionController
import com.nestra.remote.core.session.RemoteSession
import com.nestra.remote.core.session.RemoteViewer
import com.nestra.remote.core.session.ViewerTarget

/**
 * ETAP 8/9 real-backend gate for the Android client core: the SAME Kotlin code the app runs, over real HTTP, against the
 * REAL NestraRemote.Server (schema v4, pairing, ownership) + the REAL NestraRemote.AccountBridge (ES256) + a NESTRA
 * Parent auth host speaking the live two-step MFA contract. Driven by the .NET host test AndroidCoreIntegrationTests,
 * which owns the laptop agent: requests go out as "REQ ..." lines on stdout, answers come back as "RES ..." on stdin.
 * Prints only check names and results - never a password, code, session or token.
 */
fun main() {
    val env = System.getenv()
    val parentUrl = env.getValue("NR_PARENT_URL"); val remoteUrl = env.getValue("NR_REMOTE_URL"); val authUrl = env.getValue("NR_AUTH_URL")
    val wire = Recorder(UrlConnectionTransport())
    var pass = 0; var fail = 0
    fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS  $name") } else { fail++; println("FAIL  $name") } }
    fun req(what: String): List<String> { println("REQ $what"); System.out.flush(); val l = readLine() ?: error("host closed"); require(l.startsWith("RES ")); return l.substring(4).split(' ') }
    fun session() = RemoteSession(ParentAuthClient(wire, parentUrl), RemoteApi(wire, remoteUrl, authUrl), MemorySecretStore())
    fun raw(method: String, path: String, bearer: String?) = wire.inner.execute(HttpRequest(method, remoteUrl + path, bearer?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())).status

    // ---- account A: password + Parent MFA
    val a = session()
    val accountA = env.getValue("NR_A_ACCOUNT").toLong()
    check("password login with MFA enabled -> MFA required (no session yet)", a.signIn(env.getValue("NR_A_EMAIL"), env.getValue("NR_A_PASSWORD").toCharArray()) is RemoteSession.Step.MfaRequired)
    check("no Remote token was requested before MFA", wire.calls.none { it.url.endsWith("/v1/auth/token") })
    check("malformed MFA code refused locally", (a.submitMfa("12ab56") as? RemoteSession.Step.Failed)?.failure == RemoteSession.Failure.MFA_CODE_MALFORMED)
    check("wrong MFA code -> rejected by Parent", (a.submitMfa("000000") as? RemoteSession.Step.Failed)?.failure == RemoteSession.Failure.MFA_REJECTED)
    val ok = a.submitMfa(env.getValue("NR_A_TOTP"))
    check("valid MFA -> Parent /me 200 -> Remote token -> whoami -> signed in as A", ok is RemoteSession.Step.SignedIn && ok.accountId == accountA)
    val parentSessionA = wire.lastParentSessionToRemote
    check("Remote whoami = Parent account", (a.whoami() as? ApiResult.Ok)?.value == accountA)
    val meta = a.tokenMeta()
    check("Remote token ES256, aud nestra-remote, sub = account, lifetime 300 s", meta?.alg == "ES256" && meta.aud == "nestra-remote" && meta.sub == "$accountA" && meta.lifetimeSeconds == 300L && meta.kid != null)
    check("zero devices before pairing", (a.devices() as? ApiResult.Ok)?.value?.isEmpty() == true)

    // ---- pairing (ETAP 6 unchanged)
    val (exId, exCode) = req("expired-pairing")
    check("expired pairing -> invalid_or_expired", a.completePairing(PairingInput.of(exCode, exId)!!) == ApiResult.InvalidOrExpired)
    val laptopId = req("laptop-id")[0]
    val (pid, code) = req("pairing")
    val qr = PairingInput.pairingIdFromQr("nestra://pair/$pid")
    check("QR nestra://pair/<PairingId> parsed; it carries no code", qr == pid)
    val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
    check("wrong pairing code -> invalid_or_expired", a.completePairing(PairingInput.of(wrong, qr)!!) == ApiResult.InvalidOrExpired)
    val preview = a.previewPairing(PairingInput.of(code, qr)!!)
    check("pairing preview shows the laptop", (preview as? ApiResult.Ok)?.value?.deviceName == "RAFAL-LAPTOP")
    val done = a.completePairing(PairingInput.of(code, qr)!!)
    check("pairing complete -> SAME DeviceId, paired, Remote Access OFF", (done as? ApiResult.Ok)?.value?.let { it.deviceId == laptopId && it.paired && !it.remoteEnabled } == true)
    val listA = (a.devices() as? ApiResult.Ok)?.value
    check("account device list shows the laptop (same DeviceId, paired, Remote OFF, Windows)", listA?.singleOrNull()?.let { it.deviceId == laptopId && it.paired && !it.remoteEnabled && it.platform == "Windows" && !it.revoked } == true)
    check("server: Devices.AccountId = A, RemoteEnabled = 0, one record", req("db-device")[0] == "$accountA|0|1")
    check("a used pairing code cannot be replayed", a.completePairing(PairingInput.of(code, qr)!!) == ApiResult.InvalidOrExpired)

    // ---- account B (password only) cannot see / operate A's laptop
    val b = session()
    val accountB = env.getValue("NR_B_ACCOUNT").toLong()
    val sb = b.signIn(env.getValue("NR_B_EMAIL"), env.getValue("NR_B_PASSWORD").toCharArray())
    check("password login without MFA -> signed in as B", sb is RemoteSession.Step.SignedIn && sb.accountId == accountB)
    check("B does not see A's laptop", (b.devices() as? ApiResult.Ok)?.value?.none { it.deviceId == laptopId } == true)
    check("B cannot unpair A's laptop (404, no existence oracle)", b.unpair(laptopId) == ApiResult.NotFound)
    check("B probing a random DeviceId gets the same 404", b.unpair("0123456789abcdefghjkmnpqrs") == ApiResult.NotFound)
    check("B cannot claim through A's (used) pairing", b.completePairing(PairingInput.of(code, qr)!!) == ApiResult.InvalidOrExpired)
    check("still owned by A", req("db-device")[0] == "$accountA|0|1")

    // ---- token rejection
    check("invalid token -> 401", raw("GET", "/v1/auth/whoami", "x.y.z") == 401)
    check("no token -> 401", raw("GET", "/v1/account/devices", null) == 401)
    val expired = req("expired-token $accountA")[0]
    check("expired (correctly signed) token -> 401", raw("GET", "/v1/auth/whoami", expired) == 401)
    check("Parent session sent directly to Remote API -> 401", raw("GET", "/v1/auth/whoami", wire.lastParentSessionToRemote) == 401)
    check("browser cookie only -> no token", wire.inner.execute(HttpRequest("POST", "$authUrl/v1/auth/token", mapOf("Cookie" to "NestraParentSession=${wire.lastParentSessionToRemote}"))).status == 401)

    // ---- unpair, identity kept, re-pair
    check("A unpairs its laptop", a.unpair(laptopId) == ApiResult.Ok(Unit))
    check("device list empty after unpair", (a.devices() as? ApiResult.Ok)?.value?.isEmpty() == true)
    check("server: owner cleared, Remote OFF, DeviceId record kept", req("db-device")[0] == "null|0|1")
    val (pid2, code2) = req("pairing")
    val again = a.completePairing(PairingInput.of(code2, pid2)!!)
    check("re-pair -> the SAME DeviceId again, Remote Access OFF", (again as? ApiResult.Ok)?.value?.let { it.deviceId == laptopId && !it.remoteEnabled } == true)
    check("server: one record, owner A, Remote OFF", req("db-device")[0] == "$accountA|0|1")

    // ---- ETAP 9 live sessions (control plane; the viewer here is a recorder, the laptop is the host's real agent)
    class ItViewer : RemoteViewer {
        @Volatile var target: ViewerTarget? = null; @Volatile var grantSha: String? = null; @Volatile var events: RemoteViewer.Events? = null; @Volatile var closed = 0
        override fun connect(target: ViewerTarget, events: RemoteViewer.Events) {
            grantSha = java.security.MessageDigest.getInstance("SHA-256").digest(String(target.grant()).toByteArray()).joinToString("") { "%02x".format(it) }
            this.target = target; this.events = events
        }
        override fun disconnect() { closed++ }
    }
    fun waitFor(ms: Long = 10_000, cond: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }; return cond() }
    fun ctl(v: RemoteViewer) = LiveSessionController(a, v, pollMs = 100, keepaliveMs = 300, connectTimeoutMs = 20_000)
    class Bg(val c: LiveSessionController, id: String) { @Volatile var r: LiveSessionController.State? = null; val t = Thread { r = c.run(id) }.also { it.start() }; fun join(): LiveSessionController.State? { t.join(20_000); return r } }

    check("Remote Access OFF on the PC -> refused remote_access_disabled (the phone cannot enable it)",
        ctl(ItViewer()).run(laptopId) == LiveSessionController.State.Refused(LiveSessionController.Refusal.REMOTE_ACCESS_DISABLED))
    check("server: Remote Access still OFF", req("db-device")[0] == "$accountA|0|1")
    req("laptop-remote-on")
    val dev = (a.devices() as? ApiResult.Ok)?.value?.singleOrNull()
    check("device list: Remote ON, engine ready, Connect offered, no live session", dev?.let { it.canConnect && it.engineReady && it.liveSession == null } == true)
    check("B cannot start a session to A's PC (404)", b.startSession(laptopId) == ApiResult.NotFound)

    // session 1: PC accepts -> one-time connect data -> viewer -> active -> DISCONNECT in the PC tray
    val v1 = ItViewer(); val s1 = Bg(ctl(v1), laptopId)
    val (sid1, sha1) = req("laptop-accept")
    check("viewer got the PC's engine, NESTRA host + key and exactly the grant the PC holds",
        waitFor { v1.target != null } && v1.target!!.engineId == "123456789" && v1.target!!.rendezvousHost == "remote.nestraparent.com" &&
        v1.target!!.serverKey == "AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo=" && v1.grantSha == sha1)
    check("B cannot read A's session (404)", b.sessionStatus(sid1) == ApiResult.NotFound)
    check("connect data handed out once (second read has none)", (a.sessionStatus(sid1) as? ApiResult.Ok)?.value?.connect == null)
    v1.events!!.onConnected(); req("laptop-active $sid1")
    check("session active on the phone", waitFor { s1.c.state is LiveSessionController.State.Active })
    check("device list shows the live session as active", waitFor { (a.devices() as? ApiResult.Ok)?.value?.singleOrNull()?.liveSession?.state == "active" })
    req("laptop-end $sid1 local_disconnect")
    check("DISCONNECT on the PC ends the phone session (viewer closed)", s1.join() == LiveSessionController.State.Ended("local_disconnect") && v1.closed == 1)

    // session 2: Disconnect on the phone -> server -> the PC is told
    val v2 = ItViewer(); val s2 = Bg(ctl(v2), laptopId)
    val (sid2, _) = req("laptop-accept")
    waitFor { v2.target != null }; v2.events!!.onConnected(); req("laptop-active $sid2")
    waitFor { s2.c.state is LiveSessionController.State.Active }
    s2.c.disconnect()
    check("Disconnect on the phone -> ended, viewer closed", s2.join() == LiveSessionController.State.Ended("viewer_disconnect") && v2.closed == 1)
    check("the PC received session_end viewer_disconnect", req("laptop-wait-end")[0] == "viewer_disconnect")

    // session 3: Remote Access switched OFF at the PC while active
    val v3 = ItViewer(); val s3 = Bg(ctl(v3), laptopId)
    val (sid3, _) = req("laptop-accept")
    waitFor { v3.target != null }; v3.events!!.onConnected(); req("laptop-active $sid3")
    waitFor { s3.c.state is LiveSessionController.State.Active }
    req("laptop-remote-off")
    check("Remote Access OFF at the PC ends the active session on the phone", s3.join() == LiveSessionController.State.Ended("remote_access_off") && v3.closed == 1)
    check("server: Remote Access OFF again, same single record", req("db-device")[0] == "$accountA|0|1")

    // ---- logout
    a.whoami()
    val oldToken = wire.lastRemoteToken          // A's live token (the last /v1 call was A's)
    val oldParent = parentSessionA
    val rep = a.logout()
    check("logout: Remote token revoked (204) and Parent logout 200", rep.remoteRevoked && rep.parentLogoutStatus == 200)
    check("old Remote token -> 401", raw("GET", "/v1/auth/whoami", oldToken) == 401)
    check("old Parent session cannot mint a new Remote token", wire.inner.execute(HttpRequest("POST", "$authUrl/v1/auth/token", mapOf("Authorization" to "Bearer $oldParent"))).status == 401)
    check("signed out locally; calls refused without network", a.state is RemoteSession.State.SignedOut && a.devices() == ApiResult.Unauthorized)
    b.logout()
    check("no credential in any request URL", wire.calls.none { r -> listOf("token=", "session=", "code=", "password").any { r.url.contains(it, ignoreCase = true) } || r.url.contains("eyJ") })
    check("no cookie was ever sent to NESTRA Remote by the client", wire.calls.filter { it.url.startsWith(remoteUrl) || it.url.startsWith(authUrl) }.none { it.headers.containsKey("Cookie") })
    println("RESULT PASS=$pass FAIL=$fail")
}

/** Wraps the real transport; remembers request shapes and (in memory, for the revocation checks) the last credentials. */
class Recorder(val inner: HttpTransport) : HttpTransport {
    val calls = mutableListOf<HttpRequest>()
    var lastRemoteToken: String? = null
    var lastParentSessionToRemote: String? = null
    override fun execute(request: HttpRequest): HttpResponse {
        calls += request
        val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
        if (request.url.endsWith("/v1/auth/token")) lastParentSessionToRemote = bearer
        else if (request.url.contains("/v1/") && bearer != null) lastRemoteToken = bearer
        return inner.execute(request)
    }
}
