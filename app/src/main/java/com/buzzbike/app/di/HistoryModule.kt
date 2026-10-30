package com.buzzbike.app.di

import com.buzzbike.app.data.export.AndroidGpxExporter
import com.buzzbike.app.data.repository.RideRepository
import com.buzzbike.app.domain.export.GpxExporter
import com.buzzbike.app.domain.history.RideDetailSource
import com.buzzbike.app.domain.history.RideHistorySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt binding of the history read port to its Room-backed implementation.
 *
 * [RideRepository] is already a singleton with constructor injection, so this
 * provider only exposes it through the interface the history UI depends on.
 */
@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    @Provides
    @Singleton
    fun provideRideHistorySource(impl: RideRepository): RideHistorySource = impl

    @Provides
    @Singleton
    fun provideRideDetailSource(impl: RideRepository): RideDetailSource = impl

    @Provides
    @Singleton
    fun provideGpxExporter(impl: AndroidGpxExporter): GpxExporter = impl
}
