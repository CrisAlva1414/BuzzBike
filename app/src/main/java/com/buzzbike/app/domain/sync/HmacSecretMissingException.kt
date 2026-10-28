package com.buzzbike.app.domain.sync

/**
 * Raised when signing needs an HMAC secret that has not been provisioned.
 *
 * BuzzBike does not generate the webhook secret: the user creates it on the
 * NAS/Hermes side and imports the same bytes here (ADR-003). Until that
 * happens, sync must fail closed with this exception instead of falling back
 * to a random key the receiver does not know.
 *
 * The message is intentionally static and secret-free.
 */
class HmacSecretMissingException : IllegalStateException(MESSAGE) {
    private companion object {
        const val MESSAGE = "HMAC secret is not configured"
    }
}
