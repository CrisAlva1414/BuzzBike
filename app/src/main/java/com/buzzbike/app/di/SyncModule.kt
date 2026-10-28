package com.buzzbike.app.di

import com.buzzbike.app.data.local.RoomOutboxStore
import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.sync.AndroidKeystoreHmacKeyProvider
import com.buzzbike.app.sync.HmacSha256Signer
import com.buzzbike.app.sync.HttpConnectivityChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the sync stack (ADR-003).
 *
 * The cryptographic bindings and the outbox/connectivity adapters are
 * singletons: the key provider owns the keystore entry, while the signer,
 * connectivity checker and Room-backed store are stateless wrappers.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun provideHmacKeyProvider(): HmacKeyProvider = AndroidKeystoreHmacKeyProvider()

    @Provides
    @Singleton
    fun provideHmacSigner(keyProvider: HmacKeyProvider): HmacSigner = HmacSha256Signer(keyProvider)

    @Provides
    @Singleton
    fun provideConnectivityChecker(): ConnectivityChecker = HttpConnectivityChecker()

    @Provides
    @Singleton
    fun provideOutboxStore(outboxDao: OutboxDao): OutboxStore = RoomOutboxStore(outboxDao)
}
