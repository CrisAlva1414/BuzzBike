package com.buzzbike.app.di

import com.buzzbike.app.data.settings.SharedPrefsSyncConfigStore
import com.buzzbike.app.domain.sync.SyncConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt binding for the non-secret sync configuration.
 *
 * Kept separate from `SyncModule`/`SecretModule`: this module owns only the
 * preferences-backed store, so the secret provisioning wiring stays focused.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSyncConfigStore(impl: SharedPrefsSyncConfigStore): SyncConfigStore = impl
}
