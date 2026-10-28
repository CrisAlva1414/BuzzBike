package com.buzzbike.app.domain.sync

/**
 * Port for delivering a ride payload to the optional Hermes Agent.
 *
 * Lives in `domain/` on purpose: sync is opt-in, off by default, and the
 * transport details stay outside the domain core (AGENTS.md §3/§7). Slice 3.1
 * ships configuration-validation stubs only; the real HTTP transport, HMAC
 * signing and outbox belong to later slices.
 */
interface HermesSender {
    /**
     * Sends [payload] for [rideId].
     *
     * Expected transport/config problems are reported through [SendResult]
     * instead of exceptions so callers can decide whether to retry.
     */
    suspend fun sendRide(
        rideId: String,
        payload: ByteArray,
    ): SendResult
}

/** Outcome of a [HermesSender.sendRide] call. */
data class SendResult(
    val success: Boolean,
    val statusCode: Int? = null,
    val errorMessage: String? = null,
)
