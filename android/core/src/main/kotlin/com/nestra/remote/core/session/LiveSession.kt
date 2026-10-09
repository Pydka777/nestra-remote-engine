package com.nestra.remote.core.session

import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.SessionStarted
import com.nestra.remote.core.api.SessionStatus
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The three NESTRA Remote calls a live session needs (RemoteSession implements them with token refresh). */
interface LiveSessionApi {
    fun startSession(deviceId: String): ApiResult<SessionStarted>
    fun sessionStatus(sessionId: String): ApiResult<SessionStatus>
    fun endSession(sessionId: String): ApiResult<Unit>
}

/**
 * Where the viewer connects. Built only from a validated server answer: host and key are always NESTRA's own
 * (RemoteApi.RENDEZVOUS_HOST / SERVER_KEY). The grant is a wipeable copy for the native core; never logged.
 */
class ViewerTarget(val engineId: String, val rendezvousHost: String, val serverKey: String, grant: CharArray) {
    private var g: CharArray? = grant
    fun grant(): CharArray = g ?: throw IllegalStateException("grant wiped")
    fun wipe() { g?.fill('\u0000'); g = null }
    override fun toString() = "ViewerTarget(engine=$engineId, host=$rendezvousHost)"
}

/**
 * The remote desktop viewer: the RustDesk core (AGPL-3.0) inside the app, reached through JNI. It renders the REAL
 * screen of the PC and sends touch/mouse/keyboard input; it must never be replaced by screenshots.
 * Contract: connect() returns at once; events arrive on any thread; disconnect() is idempotent.
 */
interface RemoteViewer {
    fun connect(target: ViewerTarget, events: Events)
    fun disconnect()
    interface Events {
        /** The engine on the PC accepted the grant: frames and input flow. */
        fun onConnected()
        /** The connection is over (closed by the PC, network loss, refused grant, ...). [reason] is a short word. */
        fun onClosed(reason: String)
    }
}

/**
 * ETAP 9 phone side of ONE live session (no Android API; unit- and real-backend-tested on the JVM):
 *   request -> laptop accepts (engine ready) -> connect data ONCE -> viewer connects with the one-time grant -> active
 *   -> keepalive polls -> end on: Disconnect here, viewer closed, server says ended (Disconnect on the PC, Remote
 *   Access OFF, unpair, sign-out, TTL, ...), token lost. Ending is idempotent and always tells the server.
 * The app NEVER switches Remote Access on: a refused request just reports why.
 */
class LiveSessionController(
    private val api: LiveSessionApi,
    private val viewer: RemoteViewer,
    private val pollMs: Long = 1000,
    private val keepaliveMs: Long = 15_000,
    private val connectTimeoutMs: Long = 120_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** v0.2.1 diagnostics sink (the app writes it to logcat, tag NESTRA-VIEWER). Lines pass [Redact.line]. */
    private val diag: (String) -> Unit = {},
) {
    enum class Refusal { REMOTE_ACCESS_DISABLED, DEVICE_OFFLINE, ENGINE_NOT_READY, SESSION_IN_PROGRESS, NOT_FOUND, RATE_LIMITED,
        SIGNED_OUT, NETWORK, LAPTOP_REFUSED, EXPIRED, UNEXPECTED }

    sealed class State {
        object Idle : State() { override fun toString() = "Idle" }
        object Requesting : State() { override fun toString() = "Requesting" }
        object WaitingForPc : State() { override fun toString() = "WaitingForPc" }
        object Connecting : State() { override fun toString() = "Connecting" }
        data class Active(val sinceMs: Long) : State()
        data class Ended(val reason: String) : State()
        data class Refused(val why: Refusal, val detail: String? = null) : State()
    }

    private sealed class Ev {
        object Connected : Ev()
        data class Closed(val reason: String) : Ev()
        object Disconnect : Ev()
    }

    @Volatile var state: State = State.Idle; private set
    @Volatile var sessionId: String? = null; private set
    private val events = LinkedBlockingQueue<Ev>()
    var listener: ((State) -> Unit)? = null

    private fun log(msg: String) { try { diag(Redact.line(msg)) } catch (_: RuntimeException) { } }
    private fun set(s: State) { if (s != state) log("state ${state} -> ${s}"); state = s; listener?.invoke(s) }

    /** Phone "Disconnect" (any thread). [source] is only for the diagnostics (button, back, sign-out, ...). */
    fun disconnect(source: String = "app") { log("disconnect requested by $source"); events.offer(Ev.Disconnect) }

    /** Runs the whole session on the calling (background) thread; returns the final state. */
    fun run(deviceId: String): State {
        set(State.Requesting)
        log("session request for device ${Redact.id(deviceId)}")
        val sid = when (val r = api.startSession(deviceId)) {
            is ApiResult.Ok -> r.value.sessionId
            else -> { log("session request refused: ${r::class.simpleName}"); return refused(r) }
        }
        sessionId = sid
        log("session created: ${Redact.id(sid)}")
        set(State.WaitingForPc)

        // 1) wait for the laptop to accept and the one-time connect data
        val deadline = clock() + connectTimeoutMs
        var target: ViewerTarget? = null
        while (target == null) {
            if (events.poll() is Ev.Disconnect) return end(sid, "viewer_disconnect", tellViewer = false)
            when (val st = api.sessionStatus(sid)) {
                is ApiResult.Ok -> {
                    val s = st.value
                    when {
                        s.connect != null -> {
                            val c = s.connect
                            val t = ViewerTarget(c.engineId, c.rendezvousHost, c.serverKey, c.takeGrant())
                            target = t
                            log("connect data received once: engine ${Redact.engine(c.engineId)}, host ${c.rendezvousHost}, grant in memory (not logged)")
                            log(SecretDiag.describe("android_receive", t.grant()))
                        }
                        s.state == "refused" -> { sessionId = null; return final(State.Refused(Refusal.LAPTOP_REFUSED, s.endReason)) }
                        s.state == "expired" -> { sessionId = null; return final(State.Refused(Refusal.EXPIRED, s.endReason)) }
                        s.state == "ended" -> { sessionId = null; return final(State.Ended(s.endReason ?: "ended")) }
                        s.state == "connecting" || s.state == "active" ->      // data taken by someone else: never reuse
                            return end(sid, "unexpected", tellViewer = false, result = State.Refused(Refusal.UNEXPECTED, "connect_data_missing"))
                    }
                }
                ApiResult.Unauthorized -> return end(sid, "account_signed_out", tellViewer = false, result = State.Refused(Refusal.SIGNED_OUT))
                is ApiResult.Error -> return end(sid, "unexpected", tellViewer = false, result = State.Refused(Refusal.UNEXPECTED, st.code))
                else -> { /* transient: keep polling until the deadline */ }
            }
            if (target == null) {
                if (clock() >= deadline) return end(sid, "expired", tellViewer = false, result = State.Refused(Refusal.EXPIRED))
                if (events.poll(pollMs, TimeUnit.MILLISECONDS) is Ev.Disconnect) return end(sid, "viewer_disconnect", tellViewer = false)
            }
        }

        // 2) the viewer connects with the grant (the controller's copy is wiped right after)
        set(State.Connecting)
        try {
            log("viewer.connect (native session_add + io_loop)")
            viewer.connect(target, object : RemoteViewer.Events {
                override fun onConnected() { log("viewer event: onConnected (first frame)"); events.offer(Ev.Connected) }
                override fun onClosed(reason: String) { log("viewer event: onClosed($reason)"); events.offer(Ev.Closed(reason)) }
            })
        } catch (e: RuntimeException) {
            target.wipe()
            log("viewer.connect failed: ${e.message}")
            return end(sid, "viewer_failed", tellViewer = false, result = State.Ended("viewer_failed"))
        }
        target.wipe()
        log("viewer.connect returned; our grant copy wiped")

        // 3) connecting / active: viewer events + status polls (keepalive) until something ends it
        var active = false
        var nextPoll = clock()
        while (true) {
            val wait = (nextPoll - clock()).coerceAtLeast(0)
            when (val ev = events.poll(wait, TimeUnit.MILLISECONDS)) {
                Ev.Connected -> if (!active) { active = true; set(State.Active(clock())) }
                is Ev.Closed -> {
                    val closeReason = if (ev.reason.matches(Regex("^[a-z_]{1,32}$"))) ev.reason else "viewer_closed"
                    // A local PC-side DISCONNECT closes the native transport before the phone's next keepalive poll.
                    // Resolve that race by asking the server once before treating a transport close as a retryable
                    // network failure. If the server already has a terminal reason, preserve it and do NOT POST /end
                    // as viewer_disconnect. On a genuine network loss this status call fails or still shows a live
                    // session, so the existing reconnect path is unchanged.
                    if (closeReason in setOf("connection_error", "engine_closed", "engine_exited")) {
                        when (val st = api.sessionStatus(sid)) {
                            is ApiResult.Ok -> if (st.value.state in setOf("ended", "expired", "refused")) {
                                val reason = st.value.endReason ?: st.value.state
                                log("viewer closed as $closeReason but server already says ${st.value.state} ($reason)")
                                sessionId = null
                                return final(State.Ended(reason))
                            }
                            else -> { /* network loss / live session: keep the existing end + reconnect behaviour */ }
                        }
                    }
                    return end(sid, closeReason, tellViewer = false)
                }
                Ev.Disconnect -> return end(sid, "viewer_disconnect", tellViewer = true)
                null -> {
                    nextPoll = clock() + if (active) keepaliveMs else pollMs
                    when (val st = api.sessionStatus(sid)) {
                        is ApiResult.Ok -> when (st.value.state) {
                            "ended", "expired", "refused" -> {
                                log("server says ${st.value.state} (${st.value.endReason}) -> closing the viewer")
                                viewer.disconnect(); sessionId = null; return final(State.Ended(st.value.endReason ?: st.value.state))
                            }
                            else -> st.value.connect?.wipe()
                        }
                        ApiResult.Unauthorized, ApiResult.NotFound -> return end(sid, "account_signed_out", tellViewer = true)
                        else -> { /* transient; the server ends a silent viewer after its keepalive window anyway */ }
                    }
                    if (!active && clock() >= deadline) return end(sid, "expired", tellViewer = true, result = State.Refused(Refusal.EXPIRED))
                }
            }
        }
    }

    private fun refused(r: ApiResult<*>): State = final(State.Refused(when (r) {
        ApiResult.RemoteAccessDisabled -> Refusal.REMOTE_ACCESS_DISABLED
        ApiResult.DeviceOffline -> Refusal.DEVICE_OFFLINE
        ApiResult.EngineNotReady -> Refusal.ENGINE_NOT_READY
        ApiResult.SessionInProgress -> Refusal.SESSION_IN_PROGRESS
        ApiResult.NotFound -> Refusal.NOT_FOUND
        ApiResult.RateLimited -> Refusal.RATE_LIMITED
        ApiResult.Unauthorized -> Refusal.SIGNED_OUT
        ApiResult.NetworkError, ApiResult.ServiceUnavailable -> Refusal.NETWORK
        else -> Refusal.UNEXPECTED
    }))

    private fun end(sid: String, reason: String, tellViewer: Boolean, result: State = State.Ended(reason)): State {
        log("ending session ${Redact.id(sid)}: reason=$reason closeViewer=$tellViewer -> POST /v1/account/sessions/{id}/end (server records viewer_disconnect)")
        if (tellViewer) viewer.disconnect()
        val r = api.endSession(sid)                                 // idempotent; best effort (the server also times out)
        log("session end call: ${r::class.simpleName}")
        sessionId = null
        return final(result)
    }

    private fun final(s: State): State { events.clear(); set(s); return s }
}

/**
 * Redaction for diagnostics: IDs shortened, every long token-like run (grant, tokens, keys) replaced. The grant and
 * account tokens are never passed to the log in the first place; this is the second line of defence.
 */
object Redact {
    fun id(s: String?): String = if (s.isNullOrEmpty()) "-" else s.take(4) + "…"
    fun engine(s: String?): String = if (s.isNullOrEmpty()) "-" else "…" + s.takeLast(3)
    private val longToken = Regex("[A-Za-z0-9+/=_-]{24,}")
    private val longDigits = Regex("(?<!fp=)\\d{8,}")   // a fingerprint (fp=, 8 hex) stays readable even if all digits
    fun line(s: String): String = longDigits.replace(longToken.replace(s, "<redacted>")) { "…" + it.value.takeLast(3) }.take(400)
}

/** What the phone shows for a refusal (the PC-side switch is never touched from the phone). */
object LiveSessionText {
    const val REMOTE_ACCESS_DISABLED = "Remote Access is disabled on this PC. Enable it from the NESTRA Remote tray on the PC first."
    fun refusal(r: LiveSessionController.Refusal, detail: String? = null): String = when (r) {
        LiveSessionController.Refusal.REMOTE_ACCESS_DISABLED -> REMOTE_ACCESS_DISABLED
        LiveSessionController.Refusal.DEVICE_OFFLINE -> "This PC is offline. Make sure it is on and connected to the internet."
        LiveSessionController.Refusal.ENGINE_NOT_READY -> "The remote desktop engine on this PC is not ready. Update NESTRA Remote on the PC."
        LiveSessionController.Refusal.SESSION_IN_PROGRESS -> "A remote session to this PC is already in progress."
        LiveSessionController.Refusal.NOT_FOUND -> "This PC is no longer in your account."
        LiveSessionController.Refusal.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
        LiveSessionController.Refusal.SIGNED_OUT -> "You were signed out. Sign in again."
        LiveSessionController.Refusal.NETWORK -> "No connection to NESTRA Remote. Check your internet connection."
        LiveSessionController.Refusal.LAPTOP_REFUSED -> when (detail) {
            "remote_access_off" -> REMOTE_ACCESS_DISABLED
            "busy" -> "This PC is already in a remote session."
            "engine_not_installed", "engine_failed" -> "The remote desktop engine on this PC could not start."
            else -> "The PC refused the session."
        }
        LiveSessionController.Refusal.EXPIRED -> "The PC did not answer in time. Try again."
        LiveSessionController.Refusal.UNEXPECTED -> "The session could not be started."
    }
}
