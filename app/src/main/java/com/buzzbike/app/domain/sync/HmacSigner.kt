package com.buzzbike.app.domain.sync

/**
 * Port for authenticating sync payloads with HMAC-SHA256 (ADR-003).
 *
 * The signing input is exactly ADR-003's `<timestamp>.<body>`: the decimal
 * Unix epoch seconds, a `.` separator, and then the raw payload bytes — no
 * whitespace, no re-serialization. Output is lowercase hex (no `0x` prefix),
 * matching the `X-Webhook-Signature-V2` header expected by the Hermes
 * receiver (ADR-003 §"Firma HMAC V2").
 *
 * The key is never passed through this port; implementations obtain it from
 * [HmacKeyProvider] (Android Keystore in production). Keeping the contract
 * here lets the outbox depend on signing without importing `javax.crypto`
 * internals or Android classes (AGENTS.md §3).
 */
interface HmacSigner {
    /**
     * Returns the lowercase-hex HMAC-SHA256 of `"<timestamp>." + payload`.
     *
     * [timestampSeconds] is Unix epoch seconds, not milliseconds: the receiver
     * enforces the ±300 s anti-replay window defined in ADR-003.
     */
    fun sign(
        timestampSeconds: Long,
        payload: ByteArray,
    ): String

    /**
     * Verifies [signature] (lowercase or uppercase hex) against
     * `"<timestamp>." + payload`.
     *
     * Returns `false` — never throws — when [signature] is not valid hex
     * (odd length or non-hex characters) or does not match. The comparison is
     * constant-time, so a wrong signature cannot be recovered byte by byte
     * from timing.
     */
    fun verify(
        timestampSeconds: Long,
        payload: ByteArray,
        signature: String,
    ): Boolean
}
