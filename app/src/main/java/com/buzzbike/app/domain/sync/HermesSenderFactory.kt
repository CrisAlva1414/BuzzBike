package com.buzzbike.app.domain.sync

/**
 * Builds the [HermesSender] that matches [HermesConfig.senderType].
 *
 * [SenderType] is a closed enum, so the `when` is exhaustive and an unknown
 * transport cannot silently fall back to a default.
 */
object HermesSenderFactory {
    fun create(config: HermesConfig): HermesSender =
        when (config.senderType) {
            SenderType.WEBHOOK -> WebhookHermesSender(config)
            SenderType.REST -> RestHermesSender(config)
        }
}
