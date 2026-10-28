package com.buzzbike.app.di

import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.sync.AndroidKeystoreHmacKeyProvider
import com.buzzbike.app.sync.HmacSha256Signer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the sync cryptographic stack (ADR-003).
 *
 * Both bindings are singletons: the key provider owns the keystore entry and
 * the signer is stateless, so one instance per process is enough.
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
}
