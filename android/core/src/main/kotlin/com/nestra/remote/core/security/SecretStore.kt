package com.nestra.remote.core.security

import com.nestra.remote.core.auth.ParentSession

/**
 * Where the client may keep the Parent session between app starts. The Android app implements it with an Android
 * Keystore AES-256-GCM key (non-exportable) and stores only the ciphertext; never plaintext SharedPreferences.
 * Never stored by anyone: the password, the MFA code, the MFA challenge, the Remote token (memory only, 300 s).
 */
interface SecretStore {
    fun saveParentSession(accountId: Long, transport: ParentSession.Transport, value: CharArray)
    fun loadParentSession(): StoredSession?
    fun clear()

    class StoredSession(val accountId: Long, val transport: ParentSession.Transport, val value: CharArray) {
        override fun toString() = "StoredSession(account=$accountId)"
    }
}

/** Keeps nothing across restarts (the user signs in again). Also the test double. */
class MemorySecretStore : SecretStore {
    private var s: SecretStore.StoredSession? = null
    override fun saveParentSession(accountId: Long, transport: ParentSession.Transport, value: CharArray) { s = SecretStore.StoredSession(accountId, transport, value.copyOf()) }
    override fun loadParentSession() = s?.let { SecretStore.StoredSession(it.accountId, it.transport, it.value.copyOf()) }
    override fun clear() { s?.value?.fill('\u0000'); s = null }
}
