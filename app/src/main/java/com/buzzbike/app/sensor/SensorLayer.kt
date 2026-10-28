@file:Suppress("unused")

package com.buzzbike.app.sensor

/**
 * Sensor adapter package marker.
 *
 * Contains the Android sensor adapters that emit readings as `Flow`:
 * [com.buzzbike.app.sensor.location.LocationSensor] (FusedLocationProvider),
 * [com.buzzbike.app.sensor.motion.MotionSensor] and
 * [com.buzzbike.app.sensor.pressure.PressureSensor], plus the runtime
 * location-permission check
 * ([com.buzzbike.app.sensor.location.AndroidLocationPermissionChecker]).
 *
 * Dependency rule (AGENTS.md §3): `sensor` is an edge adapter that emits
 * `Flow` of readings toward `domain`/`data`. It must never be imported by
 * `domain` (no Android types in domain).
 */
internal object SensorLayer
