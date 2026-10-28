package com.buzzbike.app.domain.sync

/**
 * Port for appending a serialized ride payload to the durable sync outbox.
 *
 * The payload is opaque to this layer: the domain only knows how to build it
 * with [RidePayloadBuilder] and [RidePayloadJson]. Implementations must
 * persist it before returning so a worker can retry delivery later.
 */
interface OutboxEnqueuer {
    /**
     * Persists [payload] for [rideId] with at most [maxAttempts] delivery
     * attempts.
     *
     * Implementations must coerce [maxAttempts] to at least 1 so a ride is
     * never enqueued with zero attempts. The default keeps callers that do not
     * care about [HermesConfig.retryCount] working unchanged.
     */
    suspend fun enqueue(
        rideId: String,
        payload: ByteArray,
        maxAttempts: Int = 5,
    )
}
