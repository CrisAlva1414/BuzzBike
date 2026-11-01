package com.buzzbike.app.di

import com.buzzbike.app.data.repository.RoomBikeRepository
import com.buzzbike.app.data.repository.RoomComponentRepository
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import com.buzzbike.app.domain.wear.WearClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the garage ports to their Room implementations.
 *
 * [WearClock] is injected instead of read inside the repositories so wear
 * accumulation (elapsed days, service baseline) is deterministic in JVM tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object GarageModule {
    @Provides
    @Singleton
    fun provideWearClock(): WearClock = WearClock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideBikeRepository(impl: RoomBikeRepository): BikeRepository = impl

    @Provides
    @Singleton
    fun provideComponentRepository(impl: RoomComponentRepository): ComponentRepository = impl
}
