package com.buzzbike.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.buzzbike.app.domain.sync.HermesConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Enqueues [HermesWorker] as unique background work.
 *
 * The request is constrained to `CONNECTED` network and uses exponential
 * backoff starting at [MIN_BACKOFF_SECONDS]. Only transport configuration is
 * put into the input [Data]; no payload or secret is included.
 *
 * Not unit-tested on the JVM: it requires a real [WorkManager] instance backed
 * by an Android [Context]. [HermesConfigCodec], which produces the input data,
 * is tested separately.
 */
object HermesSyncScheduler {
    const val UNIQUE_WORK_NAME = "hermes_sync"

    // WorkManager's documented minimum backoff is 10 s; 30 s keeps retries
    // gentle for an optional, user-triggered sync.
    private const val MIN_BACKOFF_SECONDS = 30L

    /**
     * Schedules one sync pass for [config].
     *
     * `KEEP` guarantees a single in-flight sync: enqueueing while a worker is
     * already running or queued leaves the existing work untouched.
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
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
