package com.buzzbike.app.domain.sync

/**
 * Webhook transport stub for Hermes.
 *
 * Slice 3.1 validates configuration only and never touches the network. The
 * real HTTP delivery (client, HMAC signature, retries) lands in a later slice;
 * keeping the port in `domain/` until then lets the rest of the app depend on
 * the abstraction instead of the transport.
 */
class WebhookHermesSender(
    private val config: HermesConfig,
) : HermesSender {
    override suspend fun sendRide(
        rideId: String,
        payload: ByteArray,
    ): SendResult {
        val error = endpointValidationError(config.endpoint)
        return if (error == null) {
            SendResult(success = true, statusCode = HTTP_OK)
        } else {
            SendResult(success = false, errorMessage = error)
        }
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
