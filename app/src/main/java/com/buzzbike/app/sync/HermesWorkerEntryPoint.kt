package com.buzzbike.app.sync

import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.OutboxStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used from [HermesWorker].
 *
 * WorkManager instantiates workers itself, so it cannot constructor-inject
 * them; the worker reaches the singletons through this entry point instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HermesWorkerEntryPoint {
    fun outboxStore(): OutboxStore

    fun connectivityChecker(): ConnectivityChecker

    fun hmacSecretManager(): HmacSecretManager

    fun hermesSenderProvider(): HermesSenderProvider
}
