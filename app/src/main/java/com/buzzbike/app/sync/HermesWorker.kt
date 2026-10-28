package com.buzzbike.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.OutboxProcessor
import dagger.hilt.android.EntryPointAccessors

/**
 * WorkManager worker that runs one [OutboxProcessor] pass in the background.
 *
 * The worker is a thin adapter: [HermesSyncScheduler] supplies the
 * [HermesConfig] as input data, the domain processor owns the health-check and
 * retry rules, and [toSyncAction] translates the outcome into WorkManager's
 * result. If the health check is not exactly HTTP 200, the outcome becomes
 * [SyncAction.RETRY] and every row stays in the outbox for a later attempt —
 * nothing is dropped.
 *
 * No payload, endpoint or signature is ever logged: the endpoint may carry
 * credentials in its query string (AGENTS.md §7).
 *
 * Not unit-tested on the JVM: it needs an Android [Context] plus a real
 * WorkManager runtime. The pure pieces it delegates to ([HermesConfigCodec],
 * [toSyncAction] and the domain [OutboxProcessor]) are covered separately.
 */
class HermesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = inputData.toHermesConfig()
        val healthCheckUrl = config?.effectiveHealthCheckUrl()
        return if (config == null || healthCheckUrl == null) {
            Result.failure()
        } else {
            process(config, healthCheckUrl)
        }
    }

    private suspend fun process(
        config: HermesConfig,
        healthCheckUrl: String,
    ): Result {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                applicationContext,
                HermesWorkerEntryPoint::class.java,
            )
        val sender = entryPoint.hermesSenderProvider().create(config)
        val processor =
            OutboxProcessor(
                store = entryPoint.outboxStore(),
                connectivity = entryPoint.connectivityChecker(),
                sender = sender,
            )
        return processor.process(healthCheckUrl).toSyncAction().toWorkerResult()
    }
}

/**
 * Reads the worker input as strings.
 *
 * WorkManager stores arbitrary primitives in [Data]; only string
 * values are meaningful to the config codec, so anything else is ignored rather
 * than coerced.
 */
private fun Data.toHermesConfig(): HermesConfig? {
    val stringValues =
        keyValueMap
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
    return HermesConfigCodec.decode(stringValues)
}

private fun SyncAction.toWorkerResult(): Result =
    when (this) {
        SyncAction.SUCCESS -> Result.success()
        SyncAction.RETRY -> Result.retry()
        SyncAction.FAILURE -> Result.failure()
    }
