package com.nestra.remote.core.session

import com.nestra.remote.core.api.ApiResult
import com.nestra.remote.core.api.PairingDone
import com.nestra.remote.core.api.PairingPreview
import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.api.RemoteDevice
import com.nestra.remote.core.api.RemoteToken
import com.nestra.remote.core.api.SessionStarted
import com.nestra.remote.core.api.SessionStatus
import com.nestra.remote.core.auth.LoginResult
import com.nestra.remote.core.auth.MfaChallenge
import com.nestra.remote.core.auth.ParentAuthClient
import com.nestra.remote.core.auth.ParentSession
import com.nestra.remote.core.pairing.PairingInput
import com.nestra.remote.core.security.SecretStore

/**
 * The NESTRA Remote sign-in state machine (no UI, no Android API - unit- and integration-tested on the JVM):
 *   SignedOut --password--> [MfaPending --code-->] proven Parent session --exchange--> Remote token --whoami--> SignedIn
 * Invariants:
 *  - the Remote token is memory-only and re-minted from the Parent session before it expires (or once after a 401);
 *  - the Parent session is the only long-lived credential; it is Keystore-encrypted at rest (SecretStore) and wiped on
 *    sign-out; when Parent no longer accepts it the user is signed out (no silent fallback, no stored password);
 *  - an MFA challenge is never a session and is dropped after one verification attempt result other than a wrong code.
 */
class RemoteSession(
    private val parent: ParentAuthClient,
    private val api: RemoteApi,
    private val store: SecretStore,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refreshMarginSec: Long = 30,
) : LiveSessionApi {
    sealed class State {
        object SignedOut : State() { override fun toString() = "SignedOut" }
        class MfaPending internal constructor(val accountId: Long, internal val challenge: MfaChallenge) : State() { override fun toString() = "MfaPending(account=$accountId)" }
        class SignedIn(val accountId: Long) : State() { override fun toString() = "SignedIn(account=$accountId)" }
    }

    enum class Failure { INVALID_CREDENTIALS, MFA_CODE_MALFORMED, MFA_REJECTED, NOT_ESTABLISHED, ACCOUNT_MISMATCH, RATE_LIMITED,
        NETWORK, SUBSCRIPTION_EXPIRED, ACCOUNT_NOT_ACTIVE, SERVICE_UNAVAILABLE, REMOTE_REFUSED, UNEXPECTED }

    sealed class Step {
        class SignedIn(val accountId: Long) : Step()
        object MfaRequired : Step()
        class Failed(val failure: Failure) : Step() { override fun toString() = "Failed($failure)" }
    }

    class LogoutReport(val remoteRevoked: Boolean, val parentLogoutStatus: Int)

    @Volatile var state: State = State.SignedOut; private set
    private var session: ParentSession? = null
    private var token: RemoteToken? = null
    private val lock = Any()

    fun signIn(email: String, password: CharArray): Step = synchronized(lock) {
        clearLocal()
        try { finishLogin(parent.login(email, password)) } finally { password.fill('\u0000') }
    }

    fun submitMfa(code: String): Step = synchronized(lock) {
        val s = state as? State.MfaPending ?: return Step.Failed(Failure.UNEXPECTED)
        val r = parent.verifyMfa(s.challenge, code)
        if (r is LoginResult.MfaCodeMalformed) return Step.Failed(Failure.MFA_CODE_MALFORMED)   // nothing sent; may retry
        if (r is LoginResult.MfaRejected || r is LoginResult.RateLimited) {
            // Parent decides how many tries a challenge gets; the person may retry the SAME challenge or sign in again
            return Step.Failed(if (r is LoginResult.RateLimited) Failure.RATE_LIMITED else Failure.MFA_REJECTED)
        }
        state = State.SignedOut
        finishLogin(r)
    }

    /** Back to sign-in from the MFA screen (the challenge is dropped). */
    fun cancelMfa() = synchronized(lock) { if (state is State.MfaPending) state = State.SignedOut }

    /** App start: a stored Parent session is re-proven with Parent before anything else. */
    fun restore(): Step = synchronized(lock) {
        val stored = store.loadParentSession() ?: return Step.Failed(Failure.NOT_ESTABLISHED)
        val s = ParentSession.restore(stored.accountId, String(stored.value), stored.transport)
        stored.value.fill('\u0000')
        when (val me = parent.me(s)) {
            is ParentAuthClient.MeResult.Ok -> if (me.accountId != s.accountId) { s.wipe(); store.clear(); return Step.Failed(Failure.ACCOUNT_MISMATCH) }
            ParentAuthClient.MeResult.Invalid -> { s.wipe(); store.clear(); return Step.Failed(Failure.NOT_ESTABLISHED) }
            ParentAuthClient.MeResult.Unavailable -> { s.wipe(); return Step.Failed(Failure.NETWORK) }
        }
        establish(s)
    }

    fun whoami(): ApiResult<Long> = call { api.whoami(it) }
    fun devices(): ApiResult<List<RemoteDevice>> = call { api.devices(it) }
    fun previewPairing(input: PairingInput): ApiResult<PairingPreview> = call { api.previewPairing(it, input) }
    fun completePairing(input: PairingInput): ApiResult<PairingDone> = call { api.completePairing(it, input) }
    fun unpair(deviceId: String): ApiResult<Unit> = call { api.unpair(it, deviceId) }
    // ETAP 9 live sessions (the session id is not a credential; the grant inside SessionStatus.connect is)
    override fun startSession(deviceId: String): ApiResult<SessionStarted> = call { api.startSession(it, deviceId) }
    override fun sessionStatus(sessionId: String): ApiResult<SessionStatus> = call { api.sessionStatus(it, sessionId) }
    override fun endSession(sessionId: String): ApiResult<Unit> = call { api.endSession(it, sessionId) }

    /** Revokes the Remote token, ends the Parent session, clears every local secret. Always ends SignedOut. */
    fun logout(): LogoutReport = synchronized(lock) {
        val t = token; val s = session
        val revoked = t != null && api.logout(t) is ApiResult.Ok
        val parentStatus = s?.let { parent.logout(it) } ?: 0
        clearLocal()
        LogoutReport(revoked, parentStatus)
    }

    /** Test/diagnostic hook: the current token's metadata (never its value). */
    fun tokenMeta() = synchronized(lock) { token?.meta }

    // ------------------------------------------------------------------ internals
    private fun finishLogin(r: LoginResult): Step = when (r) {
        is LoginResult.Authenticated -> establish(r.session)
        is LoginResult.MfaRequired -> { state = State.MfaPending(r.challenge.accountId, r.challenge); Step.MfaRequired }
        LoginResult.InvalidCredentials -> Step.Failed(Failure.INVALID_CREDENTIALS)
        LoginResult.MfaRejected -> Step.Failed(Failure.MFA_REJECTED)
        LoginResult.MfaCodeMalformed -> Step.Failed(Failure.MFA_CODE_MALFORMED)
        LoginResult.NotEstablished -> Step.Failed(Failure.NOT_ESTABLISHED)
        LoginResult.AccountMismatch -> Step.Failed(Failure.ACCOUNT_MISMATCH)
        LoginResult.RateLimited -> Step.Failed(Failure.RATE_LIMITED)
        LoginResult.NetworkError -> Step.Failed(Failure.NETWORK)
        is LoginResult.Failed -> Step.Failed(Failure.UNEXPECTED)
    }

    private fun establish(s: ParentSession): Step {
        val t = when (val r = api.exchange(s, nowEpochSec())) {
            is ApiResult.Ok -> r.value
            else -> { s.wipe(); return Step.Failed(mapExchange(r)) }
        }
        val who = api.whoami(t)
        if (who !is ApiResult.Ok || who.value != s.accountId) { t.wipe(); s.wipe(); return Step.Failed(Failure.ACCOUNT_MISMATCH) }
        session = s; token = t
        store.saveParentSession(s.accountId, s.parentTransport, s.value.toCharArray())
        state = State.SignedIn(s.accountId)
        return Step.SignedIn(s.accountId)
    }

    private fun mapExchange(r: ApiResult<*>): Failure = when (r) {
        ApiResult.Unauthorized -> Failure.NOT_ESTABLISHED
        ApiResult.SubscriptionExpired -> Failure.SUBSCRIPTION_EXPIRED
        ApiResult.AccountNotActive -> Failure.ACCOUNT_NOT_ACTIVE
        ApiResult.RateLimited -> Failure.RATE_LIMITED
        ApiResult.NetworkError -> Failure.NETWORK
        ApiResult.ServiceUnavailable -> Failure.SERVICE_UNAVAILABLE
        else -> Failure.REMOTE_REFUSED
    }

    /** Runs [block] with a valid token: refreshes before expiry and once after a 401; signs out if Parent says no. */
    private fun <T> call(block: (RemoteToken) -> ApiResult<T>): ApiResult<T> = synchronized(lock) {
        if (state !is State.SignedIn) return ApiResult.Unauthorized
        val current = token?.takeUnless { it.expiresWithin(refreshMarginSec, nowEpochSec()) } ?: (refresh() ?: return ApiResult.Unauthorized)
        val first = block(current)
        if (first !is ApiResult.Unauthorized) return first
        val again = refresh() ?: return ApiResult.Unauthorized
        block(again)
    }

    private fun refresh(): RemoteToken? {
        val s = session ?: run { clearLocal(); return null }
        token?.wipe(); token = null
        return when (val r = api.exchange(s, nowEpochSec())) {
            is ApiResult.Ok -> r.value.also { token = it }
            ApiResult.Unauthorized, ApiResult.AccountNotActive, ApiResult.SubscriptionExpired -> { clearLocal(); null }   // Parent ended it
            else -> null                                                                                                 // transient
        }
    }

    private fun clearLocal() {
        token?.wipe(); token = null
        session?.wipe(); session = null
        store.clear()
        state = State.SignedOut
    }
}
