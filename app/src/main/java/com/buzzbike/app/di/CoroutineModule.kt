package com.buzzbike.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the dispatcher used for blocking work (Keystore, preference commits,
 * WorkManager bookkeeping).
 *
 * Injected explicitly instead of hard-coding [Dispatchers.IO] so unit tests can
 * drive the same coroutines with a `StandardTestDispatcher`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Hilt bindings for coroutine dispatchers. */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
