package com.buzzbike.app.domain.sync

import javax.crypto.SecretKey

/**
 * Provides the HMAC key used to sign sync payloads (ADR-003).
 *
 * The key is created on first use and reused afterwards. In production the
 * implementation backs it with Android Keystore (hardware-backed when
 * available), so its raw bytes are not exportable; JVM tests substitute an
 * in-memory `SecretKeySpec`.
 *
 * `javax.crypto` is JDK API, not Android: importing [SecretKey] keeps the
 * domain layer free of Android dependencies (AGENTS.md §3).
 */
interface HmacKeyProvider {
    /**
     * Returns the existing key, creating it on first call.
     *
     * Implementations may throw when the platform keystore is unusable; that
     * is an unrecoverable device-level failure, not an expected sync error.
     */
    fun getOrCreateKey(): SecretKey
}
