package com.buzzbike.app.di

import android.content.Context
import androidx.room.Room
import com.buzzbike.app.data.local.AppDatabase
import com.buzzbike.app.data.local.DatabasePassphraseProvider
import com.buzzbike.app.data.local.LegacyDatabaseCleaner
import com.buzzbike.app.data.local.MIGRATION_1_2
import com.buzzbike.app.data.local.MIGRATION_2_3
import com.buzzbike.app.data.local.dao.OutboxDao
import com.buzzbike.app.data.local.dao.RideDao
import com.buzzbike.app.data.local.dao.TrackPointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): AppDatabase {
        // The plaintext legacy database must disappear before the encrypted one
        // is created, otherwise stale unencrypted GPS data stays on disk.
        LegacyDatabaseCleaner.deletePlaintextDatabaseIfNeeded(context)
        return Room
            .databaseBuilder(
                context,
                AppDatabase::class.java,
                "buzzbike-encrypted.db",
            ).openHelperFactory(SupportOpenHelperFactory(passphraseProvider.getPassphrase()))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides
    fun provideRideDao(database: AppDatabase): RideDao = database.rideDao()

    @Provides
    fun provideTrackPointDao(database: AppDatabase): TrackPointDao = database.trackPointDao()

    @Provides
    fun provideOutboxDao(database: AppDatabase): OutboxDao = database.outboxDao()
}
