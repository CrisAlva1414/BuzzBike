package com.buzzbike.app.sync

import android.content.Context
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
 */
@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncWorkScheduler {
        override fun schedule(config: HermesConfig) {
            HermesSyncScheduler.enqueue(context, config)
        }
    }
