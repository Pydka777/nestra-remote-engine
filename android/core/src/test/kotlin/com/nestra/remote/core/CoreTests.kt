package com.nestra.remote.core

import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.auth.LoginResult
import com.nestra.remote.core.auth.ParentAuthClient
import com.nestra.remote.core.auth.ParentSession
import com.nestra.remote.core.json.Json
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.core.security.MemorySecretStore
import com.nestra.remote.core.session.RemoteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val LAPTOP = "gt0v780e1tynzcy9czjz2mrpjb"

class JsonTests {
    @Test fun roundTripAndStrictness() {
        val m = Json.obj("""{"a":1,"b":"xA\n","c":[true,null,2.5],"d":{"e":-3}}""")
        assertEquals(1L, m["a"]); assertEquals("xA\n", m["b"]); assertEquals(listOf(true, null, 2.5), m["c"])
        assertEquals(m, Json.obj(Json.write(m)))
        for (bad in listOf("""{"a":1,"a":2}""", "{", "[1,]", "01", "\"\u0001\"", "{\"a\":1} x", "[" .repeat(40) + "]".repeat(40), "1e", "-", "tru"))
            try { Json.parse(bad); fail("accepted: $bad") } catch (e: Json.ParseException) { }
    }
}

class PairingInputTests {
    @Test fun qrCarriesOnlyThePairingId() {
        assertEquals(LAPTOP, PairingInput.pairingIdFromQr("nestra://pair/$LAPTOP"))
        assertEquals(LAPTOP, PairingInput.pairingIdFromQr("  nestra://pair/$LAPTOP\n"))
        for (bad in listOf("nestra://pair/$LAPTOP?code=123456", "nestra://pair/$LAPTOP/123456", "nestra://pair/$LAPTOP#123456",
                "https://evil.example/pair/$LAPTOP", "nestra://pair/", "nestra://pair/GT0V780E1TYNZCY9CZJZ2MRPJB", "nestra://pair/gt0v780e1tynzcy9czjz2mrpjbu",
                "nestra://pair/gt0v780e1tynzcy9czjz2mrpji"))
            assertNull(bad, PairingInput.pairingIdFromQr(bad))
    }
    @Test fun codeIsSixDigitsAndNeverPrinted() {
        assertEquals("123456", PairingInput.normalizeCode("123 456")); assertEquals("123456", PairingInput.normalizeCode("123-456"))
        for (bad in listOf("12345", "1234567", "12a456", "", "١٢٣٤٥٦")) assertNull(PairingInput.normalizeCode(bad))
        val p = PairingInput.of("123456", LAPTOP)!!
        assertFalse(p.toString().contains("123456"))
        assertNull(PairingInput.of("123456", "not-an-id"))
    }
}

class ParentAuthTests {
    private fun backend() = FakeBackend().apply {
        add(FakeBackend.Account(1, "a@x", "pw-A-0123456789", mfa = true))
        add(FakeBackend.Account(2, "b@x", "pw-B-0123456789", mfa = false))
    }

    @Test fun passwordLoginWithoutMfaIsProvenByParent() {
        val b = backend(); val c = ParentAuthClient(b)
        val r = c.login("b@x", "pw-B-0123456789".toCharArray()) as LoginResult.Authenticated
        assertEquals(2L, r.session.accountId); assertEquals(ParentSession.Origin.BODY_TOKEN, r.session.origin)
        assertTrue(b.log.any { it.url.endsWith("/api/auth/me") })
        assertFalse(r.toString().contains("PARENTSESSION"))
    }

    @Test fun cookieJarOnlySessionIsFoundAndProven() {
        val b = backend().apply { cookieOnly = true }
        val r = ParentAuthClient(b).login("b@x", "pw-B-0123456789".toCharArray()) as LoginResult.Authenticated
        assertEquals(ParentSession.Origin.COOKIE_JAR, r.session.origin)
    }

    @Test fun mfaRequiredGivesAChallengeThatIsNotASession() {
        val b = backend(); val c = ParentAuthClient(b)
        val r = c.login("a@x", "pw-A-0123456789".toCharArray())
        assertTrue(r is LoginResult.MfaRequired)
        assertFalse(b.log.any { it.url.endsWith("/v1/auth/token") })
        assertFalse(r.toString().contains("CHALLENGE"))
    }

    @Test fun validMfaGivesAProvenSession() {
        val b = backend(); val c = ParentAuthClient(b)
        val ch = (c.login("a@x", "pw-A-0123456789".toCharArray()) as LoginResult.MfaRequired).challenge
        val r = c.verifyMfa(ch, "246 810") as LoginResult.Authenticated
        assertEquals(1L, r.session.accountId)
        val verify = b.log.single { it.url.endsWith("/api/auth/mfa/verify-login") }
        assertEquals(setOf("challengeToken", "code"), Json.obj(verify.body!!).keys)
    }

    @Test fun invalidReusedAndMalformedMfa() {
        val b = backend(); val c = ParentAuthClient(b)
        val ch = (c.login("a@x", "pw-A-0123456789".toCharArray()) as LoginResult.MfaRequired).challenge
        assertEquals(LoginResult.MfaRejected, c.verifyMfa(ch, "111111"))
        val before = b.log.size
        for (bad in listOf("", "12345", "abcdef", "1234567")) assertEquals(LoginResult.MfaCodeMalformed, c.verifyMfa(ch, bad))
        assertEquals("malformed codes are never sent", before, b.log.size)
        assertTrue(c.verifyMfa(ch, "246810") is LoginResult.Authenticated)
        assertEquals("a used challenge is rejected", LoginResult.MfaRejected, c.verifyMfa(ch, "246810"))
    }

    @Test fun echoedChallengeMeFailureAndAccountMismatchAreNeverSessions() {
        val b = backend().apply { echoChallenge = true }; val c = ParentAuthClient(b)
        val ch = (c.login("a@x", "pw-A-0123456789".toCharArray()) as LoginResult.MfaRequired).challenge
        assertEquals(LoginResult.NotEstablished, c.verifyMfa(ch, "246810"))
        assertFalse("the challenge is never presented as a session", b.log.any { it.headers["Authorization"]?.contains("CHALLENGE") == true })
        val b2 = backend().apply { meAccountOverride = 99 }
        assertEquals(LoginResult.AccountMismatch, ParentAuthClient(b2).login("b@x", "pw-B-0123456789".toCharArray()))
        assertEquals(LoginResult.InvalidCredentials, ParentAuthClient(backend()).login("b@x", "wrong".toCharArray()))
    }
}

class RemoteSessionTests {
    private fun world(): Triple<FakeBackend, RemoteSession, MemorySecretStore> {
        val b = FakeBackend().apply {
            add(FakeBackend.Account(1, "a@x", "pw-A-0123456789", mfa = true)); add(FakeBackend.Account(2, "b@x", "pw-B-0123456789", mfa = false))
            laptop(LAPTOP, "135790")
        }
        val store = MemorySecretStore()
        return Triple(b, RemoteSession(ParentAuthClient(b), RemoteApi(b), store, { b.now }), store)
    }

    @Test fun fullFlowMfaTokenWhoamiPairUnpairLogout() {
        val (b, s, store) = world()
        assertTrue(s.signIn("a@x", "pw-A-0123456789".toCharArray()) is RemoteSession.Step.MfaRequired)
        assertTrue(s.submitMfa("246810") is RemoteSession.Step.SignedIn)
        assertEquals(1L, (s.whoami() as ApiResult.Ok).value)
        assertEquals("ES256", s.tokenMeta()!!.alg); assertEquals(300L, s.tokenMeta()!!.lifetimeSeconds)
        assertNotNull(store.loadParentSession())
        assertEquals(0, (s.devices() as ApiResult.Ok).value.size)
        assertEquals(ApiResult.InvalidOrExpired, s.completePairing(PairingInput.of("000000")!!))
        val preview = (s.previewPairing(PairingInput.of("135790", LAPTOP)!!) as ApiResult.Ok).value
        assertEquals("RAFAL-LAPTOP", preview.deviceName)
        val done = (s.completePairing(PairingInput.of("135790", LAPTOP)!!) as ApiResult.Ok).value
        assertEquals(LAPTOP, done.deviceId); assertFalse("Remote Access stays OFF", done.remoteEnabled)
        val list = (s.devices() as ApiResult.Ok).value
        assertEquals(LAPTOP, list.single().deviceId); assertFalse(list.single().remoteEnabled); assertEquals("Windows", list.single().platform)
        assertEquals(ApiResult.Ok(Unit), s.unpair(LAPTOP))
        assertEquals(0, (s.devices() as ApiResult.Ok).value.size)
        assertEquals("one token minted for this sign-in", 1, b.tokens.size)
        val rep = s.logout()
        assertTrue(rep.remoteRevoked); assertEquals(200, rep.parentLogoutStatus)
        assertEquals("the live token is revoked at NESTRA Remote", b.tokens.keys, b.revoked)
        assertNull(store.loadParentSession()); assertEquals(RemoteSession.State.SignedOut, s.state)
        assertEquals(ApiResult.Unauthorized, s.devices())
        assertTrue("parent session ended", b.sessions.isEmpty())
    }

    @Test fun tokenIsRefreshedBeforeExpiryAndAfter401() {
        val (b, s, _) = world()
        s.signIn("b@x", "pw-B-0123456789".toCharArray())
        val minted = b.tokens.size
        b.now += 280                                  // inside the 30 s margin -> new token before the call
        assertEquals(2L, (s.whoami() as ApiResult.Ok).value); assertEquals(minted + 1, b.tokens.size)
        b.revoked += b.tokens.keys                    // server-side revocation -> one transparent re-mint
        assertEquals(2L, (s.whoami() as ApiResult.Ok).value)
    }

    @Test fun expiredOrRevokedParentSessionSignsOut() {
        val (b, s, store) = world()
        s.signIn("b@x", "pw-B-0123456789".toCharArray())
        b.sessions.clear(); b.revoked += b.tokens.keys
        assertEquals(ApiResult.Unauthorized, s.devices())
        assertEquals(RemoteSession.State.SignedOut, s.state); assertNull(store.loadParentSession())
    }

    @Test fun restoreReprovesTheStoredSessionWithParent() {
        val (b, s, store) = world()
        s.signIn("b@x", "pw-B-0123456789".toCharArray())
        val s2 = RemoteSession(ParentAuthClient(b), RemoteApi(b), store, { b.now })
        assertTrue(s2.restore() is RemoteSession.Step.SignedIn)
        b.sessions.clear()
        val s3 = RemoteSession(ParentAuthClient(b), RemoteApi(b), store, { b.now })
        assertTrue(s3.restore() is RemoteSession.Step.Failed); assertNull(store.loadParentSession())
    }

    @Test fun ownershipIsolationAndTokenSanity() {
        val (b, a, _) = world()
        a.signIn("a@x", "pw-A-0123456789".toCharArray()); a.submitMfa("246810")
        a.completePairing(PairingInput.of("135790", LAPTOP)!!)
        val bob = RemoteSession(ParentAuthClient(b), RemoteApi(b), MemorySecretStore(), { b.now })
        bob.signIn("b@x", "pw-B-0123456789".toCharArray())
        assertEquals(0, (bob.devices() as ApiResult.Ok).value.size)
        assertEquals(ApiResult.NotFound, bob.unpair(LAPTOP))
        assertEquals(ApiResult.NotFound, bob.unpair("../../admin"))
        // a token for the wrong audience is refused by the client before use
        val c = FakeBackend().apply { add(FakeBackend.Account(3, "c@x", "pw-C-0123456789", mfa = false)); tokenAudience = "something-else" }
        val cs = RemoteSession(ParentAuthClient(c), RemoteApi(c), MemorySecretStore(), { c.now })
        assertEquals(RemoteSession.Failure.REMOTE_REFUSED, (cs.signIn("c@x", "pw-C-0123456789".toCharArray()) as RemoteSession.Step.Failed).failure)
    }

    @Test fun noSecretInRequestsUrlsOrToStrings() {
        val (b, s, _) = world()
        s.signIn("a@x", "pw-A-0123456789".toCharArray()); s.submitMfa("246810")
        s.devices()
        for (r in b.log) {
            assertFalse(r.url, r.url.contains("SESSION") || r.url.contains("CHALLENGE") || r.url.contains("246810") || r.url.contains("pw-A") || r.url.contains("eyJ"))
            assertFalse(r.toString().contains("Bearer")); assertFalse(r.toString().contains("pw-A"))
            assertFalse("no cookie is ever sent to NESTRA Remote", r.url.contains("/v1/") && r.headers.containsKey("Cookie"))
        }
        assertFalse(s.state.toString().contains("CHALLENGE"))
    }
}
