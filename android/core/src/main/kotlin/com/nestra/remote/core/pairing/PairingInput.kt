package com.nestra.remote.core.pairing

import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.json.Json

/**
 * What the phone sends to /v1/pairing/preview and /v1/pairing/complete (ETAP 6, unchanged):
 *   {pairingId?, code}  - code = the 6-digit code shown on the laptop (5 min, one use, max 5 wrong tries);
 *                          pairingId = optional, from the laptop's QR "nestra://pair/<PairingId>".
 * Legacy QR identifies the pairing session only. A newer opt-in scanned QR also carries the short-lived code; the user
 * must check the device name before confirming pairing. Never log either code or QR contents.
 */
class PairingQr(val pairingId: String, val code: String?) { override fun toString() = "PairingQr(code=******)" }

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

        /** Scan a versioned QR with a short-lived code, retaining support for legacy QR payloads. */
        fun pairingFromQr(scanned: String): PairingQr? {
            val value = scanned.trim()
            val legacy = pairingIdFromQr(value)
            if (legacy != null) return PairingQr(legacy, null)
            val match = Regex("^nestra://pair/([0-9abcdefghjkmnpqrstvwxyz]{26})\\?code=([0-9]{6})$").matchEntire(value) ?: return null
            return PairingQr(match.groupValues[1], match.groupValues[2])
        }
        fun of(code: String, pairingId: String? = null): PairingInput? {
            val c = normalizeCode(code) ?: return null
            if (pairingId != null && !RemoteApi.isValidDeviceId(pairingId)) return null
            return PairingInput(pairingId, c)
        }
    }
}
