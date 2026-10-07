package com.nestra.remote.core

import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.auth.ParentAuthClient
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.core.security.MemorySecretStore
import com.nestra.remote.core.session.LiveSessionController
import com.nestra.remote.core.session.LiveSessionController.Refusal
import com.nestra.remote.core.session.LiveSessionController.State
import com.nestra.remote.core.session.LiveSessionText
import com.nestra.remote.core.session.RemoteSession
import com.nestra.remote.core.session.RemoteViewer
import com.nestra.remote.core.session.ViewerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val PC = "gt0v780e1tynzcy9czjz2mrpjb"

/** Records what the (RustDesk JNI) viewer would get; the test plays the PC side through FakeBackend. */
class FakeViewer : RemoteViewer {
    @Volatile var target: String? = null           // "engine|host|key|grant" captured at connect time (copy)
    @Volatile var events: RemoteViewer.Events? = null
    @Volatile var disconnects = 0
    var throwOnConnect = false
    override fun connect(target: ViewerTarget, events: RemoteViewer.Events) {
        if (throwOnConnect) throw IllegalStateException("native core missing")
        this.target = "${target.engineId}|${target.rendezvousHost}|${target.serverKey}|${String(target.grant())}"
        this.events = events
    }
    override fun disconnect() { disconnects++ }
}

class LiveSessionTests {
    private fun world(remoteOn: Boolean = true): Pair<FakeBackend, RemoteSession> {
        val b = FakeBackend().apply { add(FakeBackend.Account(2, "b@x", "pw-B-0123456789", mfa = false)); laptop(PC, "135790") }
        val s = RemoteSession(ParentAuthClient(b), RemoteApi(b), MemorySecretStore(), { b.now })
        assertTrue(s.signIn("b@x", "pw-B-0123456789".toCharArray()) is RemoteSession.Step.SignedIn)
        assertTrue(s.completePairing(PairingInput.of("135790", null)!!) is ApiResult.Ok)
        synchronized(b) { b.devices.getValue(PC)["remoteEnabled"] = remoteOn }
        return b to s
    }

    private fun until(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
        fail("timeout waiting for: $what")
    }

    private class Run(val c: LiveSessionController) {
        @Volatile var result: State? = null
        val states = java.util.Collections.synchronizedList(mutableListOf<State>())
        val t = Thread { result = c.run(PC) }
        init { c.listener = { states += it }; t.start() }
        fun join(): State { t.join(5000); return result ?: throw AssertionError("controller still running, state ${c.state}") }
    }

    private fun controller(s: RemoteSession, v: FakeViewer, timeout: Long = 5000) =
        LiveSessionController(s, v, pollMs = 10, keepaliveMs = 30, connectTimeoutMs = timeout)

    @Test fun deviceListCarriesEngineReadinessLiveSessionAndConnectRule() {
        val (b, s) = world(remoteOn = false)
        var d = (s.devices() as ApiResult.Ok).value.single()
        assertTrue(d.engineReady); assertNull(d.liveSession); assertFalse(d.canConnect)
        synchronized(b) { b.devices.getValue(PC)["remoteEnabled"] = true; b.devices.getValue(PC)["liveSession"] = mapOf("sessionId" to "s0000000000000000000000001", "state" to "active") }
        d = (s.devices() as ApiResult.Ok).value.single()
        assertTrue(d.canConnect); assertEquals("active", d.liveSession?.state)
        synchronized(b) { b.devices.getValue(PC)["online"] = false }
        assertFalse((s.devices() as ApiResult.Ok).value.single().canConnect)
    }

    @Test fun remoteAccessOffIsReportedWithTheExactTextAndNothingTriesToEnableIt() {
        val (b, s) = world(remoteOn = false)
        val v = FakeViewer()
        val r = controller(s, v).run(PC)
        assertEquals(State.Refused(Refusal.REMOTE_ACCESS_DISABLED), r)
        assertEquals("Remote Access is disabled on this PC. Enable it from the NESTRA Remote tray on the PC first.", LiveSessionText.refusal(Refusal.REMOTE_ACCESS_DISABLED))
        assertNull(v.target)
        assertEquals(false, synchronized(b) { b.devices.getValue(PC)["remoteEnabled"] })
        assertTrue(b.log.none { it.url.contains("remote", ignoreCase = false) && it.url.contains("enable") })
        assertTrue(b.rsessions.isEmpty())
    }

    @Test fun fullSessionGrantOnceActiveThenEndedAtThePc() {
        val (b, s) = world()
        val v = FakeViewer()
        val run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        val sid = b.onlySession()!!
        until("waiting for PC") { run.c.state == State.WaitingForPc }
        Thread.sleep(50)
        assertNull(v.target)                                                       // nothing before the PC accepted
        b.pc(sid, "accepted")
        until("viewer connect") { v.target != null }
        assertEquals("123456789|remote.nestraparent.com|AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo=|${b.grantOf(sid)}", v.target)
        assertEquals(State.Connecting, run.c.state)
        v.events!!.onConnected()
        until("active") { run.c.state is State.Active }
        b.pc(sid, "active")
        val reads = synchronized(b) { b.statusReads }
        until("keepalive polls") { synchronized(b) { b.statusReads } > reads + 2 }
        b.pc(sid, "ended", "local_disconnect")                                  // DISCONNECT in the PC tray
        assertEquals(State.Ended("local_disconnect"), run.join())
        assertEquals(1, v.disconnects)
        // the grant never travelled in a URL and is not in any state's text
        val g = b.grantOf(sid)
        assertTrue(b.log.none { it.url.contains(g) || (it.body ?: "").contains(g) })
        assertTrue(run.states.none { it.toString().contains(g) })
    }

    @Test fun phoneDisconnectEndsOnTheServerAndClosesTheViewer() {
        val (b, s) = world()
        val v = FakeViewer()
        val run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        val sid = b.onlySession()!!
        b.pc(sid, "accepted")
        until("viewer connect") { v.target != null }
        v.events!!.onConnected()
        until("active") { run.c.state is State.Active }
        run.c.disconnect()
        assertEquals(State.Ended("viewer_disconnect"), run.join())
        assertEquals(1, v.disconnects)
        assertEquals(listOf(sid), b.ends)
        assertEquals("ended", b.sessionState(sid))
        assertNull(run.c.sessionId)
    }

    @Test fun viewerClosedLaptopRefusedExpiryAndBadConnectData() {
        // viewer closed by the PC side / network -> server told
        var (b, s) = world()
        var v = FakeViewer()
        var run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        var sid = b.onlySession()!!
        b.pc(sid, "accepted"); until("connect") { v.target != null }
        v.events!!.onConnected(); until("active") { run.c.state is State.Active }
        v.events!!.onClosed("network_lost")
        assertEquals(State.Ended("network_lost"), run.join())
        assertEquals(listOf(sid), b.ends)

        // the PC refused (its own check: Remote Access OFF there)
        world().let { b = it.first; s = it.second }
        v = FakeViewer(); run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        sid = b.onlySession()!!
        b.pc(sid, "refused", "remote_access_off")
        val r = run.join()
        assertEquals(State.Refused(Refusal.LAPTOP_REFUSED, "remote_access_off"), r)
        assertEquals(LiveSessionText.REMOTE_ACCESS_DISABLED, LiveSessionText.refusal(Refusal.LAPTOP_REFUSED, "remote_access_off"))
        assertNull(v.target)

        // the PC never answers -> expired, server told
        world().let { b = it.first; s = it.second }
        v = FakeViewer()
        assertEquals(State.Refused(Refusal.EXPIRED), controller(s, v, timeout = 200).run(PC))
        assertEquals(1, b.ends.size)
        assertNull(v.target)

        // connect data pointing at ANOTHER server is never used
        world().let { b = it.first; s = it.second }
        b.connectHost = "rustdesk.example.com"
        v = FakeViewer(); run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        b.pc(b.onlySession()!!, "accepted")
        val bad = run.join()
        assertTrue(bad.toString(), bad is State.Refused && bad.why == Refusal.UNEXPECTED)
        assertNull(v.target)
        assertEquals(1, b.ends.size)

        // viewer (native core) cannot start -> ended, server told
        world().let { b = it.first; s = it.second }
        v = FakeViewer().apply { throwOnConnect = true }; run = Run(controller(s, v))
        until("requested") { b.onlySession() != null }
        b.pc(b.onlySession()!!, "accepted")
        assertEquals(State.Ended("viewer_failed"), run.join())
        assertEquals(1, b.ends.size)
    }

    @Test fun otherRefusalsMapToClearTexts() {
        val (b, s) = world()
        synchronized(b) { b.devices.getValue(PC)["online"] = false }
        assertEquals(State.Refused(Refusal.DEVICE_OFFLINE), controller(s, FakeViewer()).run(PC))
        synchronized(b) { b.devices.getValue(PC)["online"] = true; b.devices.getValue(PC)["engineReady"] = false }
        assertEquals(State.Refused(Refusal.ENGINE_NOT_READY), controller(s, FakeViewer()).run(PC))
        synchronized(b) { b.devices.getValue(PC)["engineReady"] = true }
        val c1 = Run(controller(s, FakeViewer()))
        until("first requested") { b.onlySession() != null }
        assertEquals(State.Refused(Refusal.SESSION_IN_PROGRESS), controller(s, FakeViewer()).run(PC))
        c1.c.disconnect(); c1.join()
        assertEquals(State.Refused(Refusal.NOT_FOUND), controller(s, FakeViewer()).run("0123456789abcdefghjkmnpqrs"))
        for (r in Refusal.values()) assertTrue(LiveSessionText.refusal(r).isNotBlank())
    }

    @Test fun connectDataIsSingleUseAndNeverPrinted() {
        val (b, s) = world()
        val sid = (s.startSession(PC) as ApiResult.Ok).value.sessionId
        b.pc(sid, "accepted")
        val st = (s.sessionStatus(sid) as ApiResult.Ok).value
        val c = st.connect!!
        assertFalse(c.toString().contains(b.grantOf(sid)))
        assertEquals(b.grantOf(sid), String(c.takeGrant()))
        try { c.takeGrant(); fail("grant handed out twice") } catch (e: IllegalStateException) { }
        assertNull((s.sessionStatus(sid) as ApiResult.Ok).value.connect)            // server hands it out once
        assertEquals(ApiResult.NotFound, s.sessionStatus("../../admin/v1/devices"))
        assertEquals(ApiResult.NotFound, s.startSession("not-a-device"))
        assertEquals(ApiResult.Ok(Unit), s.endSession(sid))
        assertEquals(ApiResult.Ok(Unit), s.endSession(sid))                              // idempotent
    }
}
