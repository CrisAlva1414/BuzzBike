@file:Suppress("unused")

package com.buzzbike.app.data

/**
 * Data layer package marker.
 *
 * Holds the Room stack (encrypted [com.buzzbike.app.data.local.AppDatabase],
 * DAOs, migrations), the ride façade [com.buzzbike.app.data.repository.RideRepository],
 * and the outbox adapters used by the sync pipeline
 * ([com.buzzbike.app.data.local.RoomOutboxEnqueuer],
 * [com.buzzbike.app.data.local.RoomOutboxStore]).
 *
 * Dependency rule (AGENTS.md §3): `data` is the ONLY layer that may know
 * about Room, JSON, or network. UI and domain must not import anything
 * from this package's infrastructure types.
 */
internal object DataLayer
