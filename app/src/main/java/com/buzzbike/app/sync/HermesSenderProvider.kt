package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.HermesSender
import com.buzzbike.app.domain.sync.HmacSigner
import javax.inject.Inject

/**
 * Builds the real HTTP [HermesSender] for a given [HermesConfig].
 *
 * Lives in `sync/` so the outbox worker can obtain a live transport without
 * the domain layer depending on the HTTP implementation. The former
 * network-free stub factory was removed once the real transport shipped.
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
