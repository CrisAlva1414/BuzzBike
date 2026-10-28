package com.buzzbike.app.di

import com.buzzbike.app.data.local.RoomOutboxEnqueuer
import com.buzzbike.app.data.local.RoomOutboxStore
import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.repository.RideSyncDataSourceImpl
import com.buzzbike.app.domain.sync.ConnectivityChecker
import com.buzzbike.app.domain.sync.HmacKeyProvider
import com.buzzbike.app.domain.sync.HmacSigner
import com.buzzbike.app.domain.sync.OutboxEnqueuer
import com.buzzbike.app.domain.sync.OutboxStore
import com.buzzbike.app.domain.sync.RideSyncCoordinator
import com.buzzbike.app.domain.sync.RideSyncDataSource
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import com.buzzbike.app.sync.HmacSha256Signer
import com.buzzbike.app.sync.HttpConnectivityChecker
import com.buzzbike.app.sync.WorkManagerSyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the outbox/transport side of the sync stack (ADR-003).
 *
 * HMAC secret provisioning lives in [SecretModule]; this module consumes the
 * resulting [HmacKeyProvider]. The signer, connectivity checker and
 * Room-backed store are stateless wrappers, so they are singletons only to
 * avoid rebuilding them per injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun provideHmacSigner(keyProvider: HmacKeyProvider): HmacSigner = HmacSha256Signer(keyProvider)

    @Provides
    @Singleton
    fun provideConnectivityChecker(hmacSigner: HmacSigner): ConnectivityChecker = HttpConnectivityChecker(hmacSigner)

    @Provides
    @Singleton
    fun provideOutboxStore(outboxDao: OutboxDao): OutboxStore = RoomOutboxStore(outboxDao)

    @Provides
    @Singleton
    fun provideRideSyncDataSource(impl: RideSyncDataSourceImpl): RideSyncDataSource = impl

    @Provides
    @Singleton
    fun provideOutboxEnqueuer(impl: RoomOutboxEnqueuer): OutboxEnqueuer = impl

    @Provides
    @Singleton
    fun provideSyncWorkScheduler(impl: WorkManagerSyncScheduler): SyncWorkScheduler = impl

    @Provides
    @Singleton
    fun provideRideSyncCoordinator(
        dataSource: RideSyncDataSource,
        enqueuer: OutboxEnqueuer,
        scheduler: SyncWorkScheduler,
    ): RideSyncCoordinator = RideSyncCoordinator(dataSource, enqueuer, scheduler)
}
