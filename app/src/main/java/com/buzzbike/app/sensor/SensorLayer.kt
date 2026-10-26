@file:Suppress("unused")

package com.buzzbike.app.sensor

/**
 * Package marker — slice 3+ will add Android sensor adapters here
 * (FusedLocationProvider GPS, accelerometer, gyroscope, barometer).
 *
 * Dependency rule (AGENTS.md §3): `sensor` is an edge adapter that emits
 * `Flow` of readings toward `domain`/`data`. It must never be imported by
 * `domain` (no Android types in domain).
 */
internal object SensorLayer
