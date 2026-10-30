package com.buzzbike.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.buzzbike.app.domain.sync.HermesConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Enqueues [HermesWorker] as unique background work.
 *
 * Both the one-time pass and the periodic drain are constrained to `CONNECTED`
 * network and use exponential backoff starting at [MIN_BACKOFF_SECONDS]. Only
 * transport configuration is put into the input [Data]; no payload or secret
 * is included.
 *
 * The periodic request is the safety net that empties the outbox even when no
 * new ride finishes: WorkManager's minimum interval is 15 minutes.
 *
 * Not unit-tested on the JVM: it requires a real [WorkManager] instance backed
 * by an Android [Context]. [HermesConfigCodec], which produces the input data,
 * is tested separately.
 */
object HermesSyncScheduler {
    const val UNIQUE_WORK_NAME = "hermes_sync"
    const val PERIODIC_WORK_NAME = "hermes_sync_periodic"

    // WorkManager's documented minimum backoff is 10 s; 30 s keeps retries
    // gentle for an optional, user-triggered sync.
    private const val MIN_BACKOFF_SECONDS = 30L

    // WorkManager rejects periodic intervals below 15 minutes.
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    /**
     * Schedules one sync pass for [config].
     *
     * `REPLACE` is deliberate: when the user changes the endpoint or the
     * health-check URL, the queued request must carry the new input data
     * instead of keeping a stale configuration until the next pass. A running
     * worker is cancelled cooperatively and replaced.
     *
     * @return the id of the enqueued request.
     */
    fun enqueue(
        context: Context,
        config: HermesConfig,
    ): UUID {
        val request =
            OneTimeWorkRequestBuilder<HermesWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putAll(HermesConfigCodec.encode(config)).build())
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /**
     * Ensures a periodic drain exists for [config].
     *
     * `UPDATE` refreshes the input data and constraints when the user changes
     * the endpoint, instead of keeping a stale periodic request forever. The
     * interval is WorkManager's minimum (15 minutes) because sync is optional
     * and the periodic pass only exists to flush rows left from an earlier
     * failed run.
     */
    fun schedulePeriodic(
        context: Context,
        config: HermesConfig,
    ) {
        val request = periodicRequest(config)
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun periodicRequest(config: HermesConfig): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<HermesWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putAll(HermesConfigCodec.encode(config)).build())
            .build()
}
