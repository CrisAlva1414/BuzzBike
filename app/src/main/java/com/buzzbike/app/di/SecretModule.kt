package com.buzzbike.app.di

import android.content.Context
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSecretManager
import com.buzzbike.app.domain.sync.SecretSealer
import com.buzzbike.app.domain.sync.SecretStorage
import com.buzzbike.app.sync.AndroidKeystoreSecretSealer
import com.buzzbike.app.sync.SharedPrefsSecretStorage
import com.buzzbike.app.sync.StoredHmacKeyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for HMAC secret provisioning (ADR-003, envelope encryption).
 *
 * Kept separate from `SyncModule` to stay under the project's detekt
 * `TooManyFunctions` threshold for objects; the concern is also distinct: this
 * module owns the keystore-backed sealer, the at-rest blob and the key
 * provider, while `SyncModule` owns the outbox/transport wiring.
 *
 * The sealer and storage are singletons because each owns a platform resource
 * (a keystore alias and a preferences file) that must not be duplicated.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecretModule {
    @Provides
    @Singleton
    fun provideSecretSealer(): SecretSealer = AndroidKeystoreSecretSealer()

    @Provides
    @Singleton
    fun provideSecretStorage(
        @ApplicationContext context: Context,
    ): SecretStorage = SharedPrefsSecretStorage(context)

    @Provides
    @Singleton
    fun provideHmacKeyProvider(
        sealer: SecretSealer,
        storage: SecretStorage,
    ): HmacKeyProvider = StoredHmacKeyProvider(sealer, storage)

    @Provides
    @Singleton
    fun provideHmacSecretManager(
        sealer: SecretSealer,
        storage: SecretStorage,
    ): HmacSecretManager = HmacSecretManager(sealer, storage)
}
