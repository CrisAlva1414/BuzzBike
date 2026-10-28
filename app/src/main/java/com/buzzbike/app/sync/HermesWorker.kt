package com.buzzbike.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.buzzbike.app.domain.sync.HermesConfig
import com.buzzbike.app.domain.sync.OutboxProcessor
import com.buzzbike.app.domain.sync.endpointValidationError
import dagger.hilt.android.EntryPointAccessors

/**
 * WorkManager worker that runs one [OutboxProcessor] pass in the background.
 *
 * The worker is a thin adapter: [HermesSyncScheduler] supplies the
 * [HermesConfig] as input data, the domain processor owns the health-check and
 * retry rules, and [toSyncAction] translates the outcome into WorkManager's
 * result. The dedicated health-check URL is mandatory (there is no fallback to
 * the send endpoint) and both URLs are validated before any network work. The
 * HMAC secret presence is read from [com.buzzbike.app.domain.sync.HmacSecretManager]
 * and forwarded to [OutboxProcessor], so a missing secret is a terminal
 * configuration error rather than an endless retry.
 *
 * If the health check is not exactly HTTP 200, the outcome becomes
 * [SyncAction.RETRY] and every row stays in the outbox for a later attempt —
 * nothing is dropped.
 *
 * No payload, endpoint or signature is ever logged (AGENTS.md §7).
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
        return if (config == null || healthCheckUrl == null || isInvalid(config)) {
            Result.failure()
        } else {
            process(config, healthCheckUrl)
        }
    }

    /** Both URLs must be valid HTTPS endpoints; otherwise retrying is futile. */
    private fun isInvalid(config: HermesConfig): Boolean =
        config.healthCheckValidationError() != null ||
            endpointValidationError(config.endpoint) != null

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
        val hmacKeyConfigured = entryPoint.hmacSecretManager().hasSecret()
        return processor
            .process(healthCheckUrl, hmacKeyConfigured)
            .toSyncAction()
            .toWorkerResult()
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
