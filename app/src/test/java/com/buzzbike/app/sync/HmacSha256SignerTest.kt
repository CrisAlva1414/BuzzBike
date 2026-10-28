package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HmacKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [HmacSha256Signer].
 *
 * ADR-003 fixes the wire format: lowercase hex of HMAC-SHA256 over
 * `"<timestamp>." + payload`. The RFC 4231 Test Case 2 vector is checked in
 * two steps so the expected constants never come from the code under test:
 *  1. the published vector is confirmed on its own, with no prefix, anchoring
 *     the key/data to the RFC; and
 *  2. the expected signature for the same key/data with a timestamp prefix is
 *     derived with an independent [Mac] (HMAC-SHA256 of
 *     `"0.what do ya want for nothing?"`) and then pinned as a hex literal.
 *
 * That way a regression in `sign()` cannot make the test agree with itself.
 */
class HmacSha256SignerTest {
    private val signer = HmacSha256Signer(FakeHmacKeyProvider(RFC_KEY))

    @Test
    fun rfc4231TestCase2_when_rawHmacComputed_then_matchesPublishedVector() {
        assertEquals(RFC_4231_TEST_CASE_2_HEX, rawHmac(RFC_DATA).toHex())
    }

    @Test
    fun given_rfc4231Payload_when_signWithTimestamp_then_matchesDerivedVector() {
        // Adaptation: sign() prepends "<timestamp>." per ADR-003, so the RFC
        // input becomes "0.what do ya want for nothing?" for timestamp 0.
        assertEquals(RFC_4231_PREFIXED_SIGNATURE_HEX, rawHmac(PREFIXED_RFC_DATA).toHex())
        assertEquals(RFC_4231_PREFIXED_SIGNATURE_HEX, signer.sign(RFC_TIMESTAMP, RFC_DATA))
    }

    @Test
    fun given_sameTimestampAndPayload_when_signTwice_then_signaturesAreEqual() {
        val first = signer.sign(TIMESTAMP, PAYLOAD)
        val second = signer.sign(TIMESTAMP, PAYLOAD)

        assertEquals(first, second)
    }

    @Test
    fun given_matchingSignature_when_verify_then_true() {
        val signature = signer.sign(TIMESTAMP, PAYLOAD)

        assertTrue(signer.verify(TIMESTAMP, PAYLOAD, signature))
    }

    @Test
    fun given_alteredPayload_when_verify_then_false() {
        val signature = signer.sign(TIMESTAMP, PAYLOAD)

        assertFalse(signer.verify(TIMESTAMP, ALTERED_PAYLOAD, signature))
    }

    @Test
    fun given_alteredTimestamp_when_verify_then_false() {
        val signature = signer.sign(TIMESTAMP, PAYLOAD)

        assertFalse(signer.verify(TIMESTAMP + 1, PAYLOAD, signature))
    }

    @Test
    fun given_invalidHexSignature_when_verify_then_false() {
        // Non-hex character, and valid characters but odd length.
        assertFalse(signer.verify(TIMESTAMP, PAYLOAD, NON_HEX_SIGNATURE))
        assertFalse(signer.verify(TIMESTAMP, PAYLOAD, ODD_LENGTH_SIGNATURE))
    }

    @Test
    fun given_emptyPayload_when_signAndVerify_then_noCrash() {
        val signature = signer.sign(TIMESTAMP, EMPTY_PAYLOAD)

        assertEquals(EMPTY_PAYLOAD_SIGNATURE_HEX, signature)
        assertTrue(signer.verify(TIMESTAMP, EMPTY_PAYLOAD, signature))
    }

    /** Independent HMAC-SHA256 with the RFC key, used to anchor the vectors. */
    private fun rawHmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(RFC_KEY, ALGORITHM))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

    private class FakeHmacKeyProvider(
        private val key: ByteArray,
    ) : HmacKeyProvider {
        override fun getOrCreateKey(): SecretKey = SecretKeySpec(key, ALGORITHM)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"

        /** RFC 4231 §4.2 key: the ASCII string "Jefe". */
        val RFC_KEY = "Jefe".toByteArray(StandardCharsets.UTF_8)

        /** RFC 4231 §4.2 data. */
        val RFC_DATA = "what do ya want for nothing?".toByteArray(StandardCharsets.UTF_8)

        /** ADR-003 signing input for timestamp 0 and the RFC data. */
        val PREFIXED_RFC_DATA = "0.what do ya want for nothing?".toByteArray(StandardCharsets.UTF_8)

        /** Timestamp used to adapt the RFC vector to the ADR-003 prefix. */
        const val RFC_TIMESTAMP = 0L

        /** RFC 4231 §4.2 expected HMAC-SHA256, lowercase hex. */
        const val RFC_4231_TEST_CASE_2_HEX =
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

        /**
         * HMAC-SHA256("Jefe", "0.what do ya want for nothing?"), lowercase hex.
         * Derived independently from the RFC vector with the timestamp prefix.
         */
        const val RFC_4231_PREFIXED_SIGNATURE_HEX =
            "37f471929915ccd2cbbe79feb84ffcff4f2bb25e15fc41c2506687331ae179cc"

        /** Unix epoch seconds used for the non-RFC cases. */
        const val TIMESTAMP = 1_700_000_000L

        val PAYLOAD = """{"rideId":"test-ride","distanceMeters":1234.5}""".toByteArray(StandardCharsets.UTF_8)
        val ALTERED_PAYLOAD = """{"rideId":"test-ride","distanceMeters":9999.9}""".toByteArray(StandardCharsets.UTF_8)
        val EMPTY_PAYLOAD = ByteArray(0)

        /** HMAC-SHA256("Jefe", "1700000000."), lowercase hex — empty body. */
        const val EMPTY_PAYLOAD_SIGNATURE_HEX = "80201faaa9258f8cfe66f88ad9fc60a52f73dd0e34d0aa77e8903d388732fc5e"

        /** `z` is outside the hex alphabet. */
        const val NON_HEX_SIGNATURE = "zz"

        /** 31 characters: no whole byte boundary. */
        const val ODD_LENGTH_SIGNATURE = "abc"
    }
}
