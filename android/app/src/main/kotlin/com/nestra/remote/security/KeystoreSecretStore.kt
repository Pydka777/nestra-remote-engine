package com.nestra.remote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nestra.remote.core.auth.ParentSession
import com.nestra.remote.core.security.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Parent session at rest: AES-256-GCM with a NON-EXPORTABLE Android Keystore key (hardware-backed where the phone
 * has it). SharedPreferences hold only IV + ciphertext; the plaintext never touches disk. Backup/transfer is disabled in
 * the manifest. Any decryption problem (key invalidated, tampered data) clears everything -> the user signs in again.
 * Never stored: password, MFA code, MFA challenge, Remote token.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("nestra_remote_secure_v1", Context.MODE_PRIVATE)

    private companion object {
        const val ALIAS = "nestra_remote_parent_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val K_IV = "iv"; const val K_CT = "ct"
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return g.generateKey()
    }

    override fun saveParentSession(accountId: Long, transport: ParentSession.Transport, value: CharArray) {
        val plain = "v1\n$accountId\n${transport.name}\n".toByteArray(Charsets.UTF_8) + String(value).toByteArray(Charsets.UTF_8)
        try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain)
            prefs.edit()
                .putString(K_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .commit()
        } catch (e: Exception) {
            clear()                                     // never fall back to plaintext
        } finally {
            plain.fill(0); value.fill('\u0000')
        }
    }

    override fun loadParentSession(): SecretStore.StoredSession? {
        val iv = prefs.getString(K_IV, null) ?: return null
        val ct = prefs.getString(K_CT, null) ?: return null
        var plain: ByteArray? = null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            plain = c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
            val parts = String(plain, Charsets.UTF_8).split('\n', limit = 4)
            if (parts.size != 4 || parts[0] != "v1") { clear(); return null }
            SecretStore.StoredSession(parts[1].toLong(), ParentSession.Transport.valueOf(parts[2]), parts[3].toCharArray())
        } catch (e: Exception) {
            clear(); null
        } finally {
            plain?.fill(0)
        }
    }

    override fun clear() {
        prefs.edit().clear().commit()
        try { keyStore().deleteEntry(ALIAS) } catch (e: Exception) { }
    }
}
