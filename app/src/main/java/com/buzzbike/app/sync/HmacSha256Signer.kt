package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSigner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac

/**
 * [HmacSigner] backed by JDK `javax.crypto` (HMAC-SHA256, lowercase hex output).
 *
 * Signing input follows ADR-003: decimal Unix epoch seconds, a `.` separator
 * and the raw payload bytes — `"<timestamp>." + payload`. The output is
 * lowercase hex with no `0x` prefix, which is what the Hermes receiver
 * expects in `X-Webhook-Signature-V2` (ADR-003 §"Firma HMAC V2"). The key is
 * fetched from [HmacKeyProvider] on every call, so this class never caches
 * secret material.
 */
class HmacSha256Signer(
    private val keyProvider: HmacKeyProvider,
) : HmacSigner {
    override fun sign(
        timestampSeconds: Long,
        payload: ByteArray,
    ): String = rawSignature(timestampSeconds, payload).toLowerHex()

    override fun verify(
        timestampSeconds: Long,
        payload: ByteArray,
        signature: String,
    ): Boolean {
        val provided = decodeHex(signature) ?: return false
        return MessageDigest.isEqual(rawSignature(timestampSeconds, payload), provided)
    }

    /** Computes the raw HMAC bytes; the caller decides how to encode them. */
    private fun rawSignature(
        timestampSeconds: Long,
        payload: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keyProvider.getOrCreateKey())
        mac.update("$timestampSeconds$SEPARATOR".toByteArray(StandardCharsets.UTF_8))
        mac.update(payload)
        return mac.doFinal()
    }

    /** Encodes these bytes as lowercase hex, two digits per byte, no `0x`. */
    private fun ByteArray.toLowerHex(): String {
        val bytes = this
        return buildString(bytes.size * NIBBLES_PER_BYTE) {
            for (byte in bytes) {
                val value = byte.toInt()
                append(HEX_DIGITS[value ushr HEX_SHIFT and HEX_MASK])
                append(HEX_DIGITS[value and HEX_MASK])
            }
        }
    }

    /**
     * Decodes [hex]; returns `null` when it has odd length or any non-hex
     * character. Both letter cases are accepted so callers are not forced to
     * normalise before verifying.
     */
    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length % NIBBLES_PER_BYTE != 0) return null
        val bytes = ByteArray(hex.length / NIBBLES_PER_BYTE)
        var valid = true
        for (index in bytes.indices) {
            val high = hexDigitValue(hex[NIBBLES_PER_BYTE * index])
            val low = hexDigitValue(hex[NIBBLES_PER_BYTE * index + 1])
            if (high == null || low == null) {
                valid = false
                break
            }
            bytes[index] = (high shl HEX_SHIFT or low).toByte()
        }
        return bytes.takeIf { valid }
    }

    /** Maps one hex digit (`0-9`, `a-f`, `A-F`) to its value, or `null`. */
    private fun hexDigitValue(char: Char): Int? =
        when (char) {
            in '0'..'9' -> char.digitToInt()
            in 'a'..'f' -> char - 'a' + DECIMAL_DIGITS
            in 'A'..'F' -> char - 'A' + DECIMAL_DIGITS
            else -> null
        }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val SEPARATOR = "."
        const val HEX_DIGITS = "0123456789abcdef"
        const val HEX_SHIFT = 4
        const val HEX_MASK = 0x0F
        const val DECIMAL_DIGITS = 10

        /** Two hex digits (nibbles) encode one byte. */
        const val NIBBLES_PER_BYTE = 2
    }
}
