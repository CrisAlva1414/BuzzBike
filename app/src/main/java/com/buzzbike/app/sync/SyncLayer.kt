@file:Suppress("unused")

package com.buzzbike.app.sync

/**
 * Package marker — slice 4+ will add the Hermes sync stack here: Room
 * outbox, WorkManager worker, HMAC signing (secret in Android Keystore),
 * and the HTTP client.
 *
 * Dependency rule (AGENTS.md §3): `sync` is transport-only. No domain
 * calculation lives here. Privacy (AGENTS.md §7): sync is opt-in, off by
 * default, data-minimized (aggregated metrics + decimated waypoints — NOT
 * raw high-frequency GPS).
 */
internal object SyncLayer
