package com.buzzbike.app.domain.export

/**
 * Persists [ride] as a GPX document at a user-chosen destination.
 *
 * [destination] is an opaque URI string (for example a Storage Access
 * Framework tree document) parsed by the Android implementation. The port
 * stays Android-free so the ViewModel can be tested with a fake, and the
 * implementation owns serialization and IO off the main thread.
 */
interface GpxExporter {
    suspend fun export(
        destination: String,
        ride: GpxRide,
    ): Result<Unit>
}
