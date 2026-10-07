package com.nestra.remote.core.pairing

import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.json.Json

/**
 * What the phone sends to /v1/pairing/preview and /v1/pairing/complete (ETAP 6, unchanged):
 *   {pairingId?, code}  - code = the 6-digit code shown on the laptop (5 min, one use, max 5 wrong tries);
 *                          pairingId = optional, from the laptop's QR "nestra://pair/<PairingId>".
 * The QR identifies the pairing session only. It must never carry the code: a QR with anything after the PairingId is
 * refused, and the code is always typed (or confirmed) by the person.
 */
class PairingInput private constructor(val pairingId: String?, code: String) {
    private val code: String = code
    internal fun body(): String = Json.write(if (pairingId != null) linkedMapOf("pairingId" to pairingId, "code" to code) else linkedMapOf("code" to code))
    override fun toString() = "PairingInput(pairingId=${pairingId ?: "-"}, code=******)"

    companion object {
        const val QR_PREFIX = "nestra://pair/"
        private val SIX = Regex("^[0-9]{6}$")

        /** Six digits from what a person typed ("123 456", "123-456"), or null. */
        fun normalizeCode(typed: String): String? = typed.filter { it != ' ' && it != '-' && it != '\u00a0' }.takeIf { SIX.matches(it) }

        /** PairingId from a scanned QR / deep link, or null when it is not exactly nestra://pair/<26-char id>. */
        fun pairingIdFromQr(scanned: String): String? {
            val s = scanned.trim()
            if (!s.startsWith(QR_PREFIX)) return null
            val id = s.substring(QR_PREFIX.length)
            return id.takeIf { RemoteApi.isValidDeviceId(it) }      // anything extra (/, ?, #, a code) -> refused
        }

        fun of(code: String, pairingId: String? = null): PairingInput? {
            val c = normalizeCode(code) ?: return null
            if (pairingId != null && !RemoteApi.isValidDeviceId(pairingId)) return null
            return PairingInput(pairingId, c)
        }
    }
}
