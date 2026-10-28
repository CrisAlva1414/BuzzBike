package com.buzzbike.app.domain.sync

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [HermesSenderFactory].
 *
 * The factory must map each [SenderType] to its concrete sender without a
 * silent fallback.
 */
class HermesSenderFactoryTest {
    private val endpoint = "https://example.com/hermes"

    @Test
    fun given_webhookType_when_create_then_returnsWebhookSender() {
        val sender =
            HermesSenderFactory.create(
                HermesConfig(endpoint = endpoint, senderType = SenderType.WEBHOOK),
            )

        assertTrue(sender is WebhookHermesSender)
    }

    @Test
    fun given_restType_when_create_then_returnsRestSender() {
        val sender =
            HermesSenderFactory.create(
                HermesConfig(endpoint = endpoint, senderType = SenderType.REST),
            )

        assertTrue(sender is RestHermesSender)
    }

    @Test
    fun given_defaultSenderType_when_create_then_returnsWebhookSender() {
        val sender = HermesSenderFactory.create(HermesConfig(endpoint = endpoint))

        assertTrue(sender is WebhookHermesSender)
    }
}
