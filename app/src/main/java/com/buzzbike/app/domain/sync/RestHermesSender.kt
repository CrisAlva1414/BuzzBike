package com.buzzbike.app.domain.sync

/**
 * REST transport stub for Hermes.
 *
 * Same contract as [WebhookHermesSender]: slice 3.1 validates configuration
 * only and never touches the network. The real REST client arrives in a later
 * slice together with HMAC signing and the outbox.
 */
class RestHermesSender(
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
