package com.nestra.remote.core.session

import java.nio.CharBuffer
import java.security.MessageDigest

/**
 * ETAP 9 secret-handoff diagnostics. A one-time secret (the session grant) is NEVER logged; only
 * `secret stage=<stage> len=<UTF-8 bytes> fp=<first 8 hex of SHA-256 over the UTF-8 bytes> enc=<class>`.
 * 32 of 256 bits identify the value across stages and reveal nothing usable. The API, the Windows agent and the engine
 * (nestra_config::secret_diag) produce the identical line, so one grant can be followed end to end.
 * Test vector: "NESTRA_test-vector_0123456789abcdefghijklmn" -> len=43 fp=4ee44f50 enc=base64url-ascii.
 * Works on the CharArray directly: no String copy of the grant is created; the encoded bytes are wiped.
 */
object SecretDiag {
    fun describe(stage: String, secret: CharArray): String {
        val buf = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        if (buf.hasArray()) buf.array().fill(0)
        try {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            val fp = (0 until 4).joinToString("") { "%02x".format(d[it].toInt() and 0xFF) }
            val enc = when {
                secret.isNotEmpty() && secret.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } -> "base64url-ascii"
                secret.all { it.code < 0x80 } -> "ascii-other"
                else -> "non-ascii"
            }
            return "secret stage=$stage len=${bytes.size} fp=$fp enc=$enc"
        } finally {
            bytes.fill(0)
        }
    }
}
