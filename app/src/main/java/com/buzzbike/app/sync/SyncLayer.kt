@file:Suppress("unused")

package com.buzzbike.app.sync

/**
 * Sync stack package marker.
 *
 * Contains the real Hermes transport and its wiring:
 * [com.buzzbike.app.sync.HttpHermesSender] built by
 * [com.buzzbike.app.sync.HermesSenderProvider],
 * [com.buzzbike.app.sync.HmacSha256Signer], the HMAC secret storage backed by
 * the Android Keystore ([com.buzzbike.app.sync.AndroidKeystoreSecretSealer],
 * [com.buzzbike.app.sync.SharedPrefsSecretStorage]), the connectivity probe
 * ([com.buzzbike.app.sync.HttpConnectivityChecker]) and the WorkManager entry
 * points ([com.buzzbike.app.sync.HermesWorker],
 * [com.buzzbike.app.sync.WorkManagerSyncScheduler]).
 *
 * Dependency rule (AGENTS.md §3): `sync` is transport-only. No domain
 * calculation lives here. Privacy (AGENTS.md §7): sync is opt-in, off by
 * default, data-minimized (aggregated metrics + decimated waypoints — NOT
 * raw high-frequency GPS).
 */
internal object SyncLayer
