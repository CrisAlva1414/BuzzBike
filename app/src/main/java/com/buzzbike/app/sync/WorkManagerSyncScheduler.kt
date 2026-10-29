package com.buzzbike.app.sync

import android.content.Context
import androidx.work.WorkManager
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.SyncWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncWorkScheduler] backed by [HermesSyncScheduler].
 *
 * Keeps WorkManager out of `domain/`: the domain only sees the port, while
 * this adapter owns the application context and the unique-work policy.
 *
 * Scheduling does two things: it requests an immediate pass for the ride that
 * just finished, and it makes sure the periodic drain exists. The periodic
 * worker is what empties the outbox later when no new ride triggers sync, so
 * previously pending rows cannot stay orphaned.
 */
@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncWorkScheduler {
        override fun schedule(config: HermesConfig) {
            HermesSyncScheduler.enqueue(context, config)
            HermesSyncScheduler.schedulePeriodic(context, config)
        }

        /**
         * Cancels both unique works by name.
         *
         * WorkManager 2.10 only exposes `cancelUniqueWork`; it applies to the
         * one-time and the periodic unique work alike, so revoking sync leaves
         * no scheduled delivery behind.
         */
        override fun cancel() {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(HermesSyncScheduler.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(HermesSyncScheduler.PERIODIC_WORK_NAME)
        }
    }
