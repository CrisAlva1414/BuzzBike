package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HermesSender
import com.buzzbike.app.domain.sync.HmacSigner
import javax.inject.Inject

/**
 * Builds the real HTTP [HermesSender] for a given [HermesConfig].
 *
 * Lives in `sync/` so the outbox worker (slice 3.2.4c) can obtain a live
 * transport without depending on [com.buzzbike.app.domain.sync.HermesSenderFactory],
 * which still returns the network-free stubs for slice 3.1.
 *
 * The signer is injected once and shared: [HmacSha256Signer] is stateless and
 * fetches the key from [com.buzzbike.app.domain.sync.HmacKeyProvider] on every
 * call, so there is no secret to cache here.
 */
class HermesSenderProvider
    @Inject
    constructor(
        private val hmacSigner: HmacSigner,
    ) {
        /** Creates a sender bound to [config] with the injected signer. */
        fun create(config: HermesConfig): HermesSender = HttpHermesSender(config, hmacSigner)
    }
