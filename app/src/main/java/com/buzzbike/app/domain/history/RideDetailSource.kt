package com.buzzbike.app.domain.history

/**
 * Read-only port that exposes a single completed ride with its track points.
 *
 * Returns `null` for an unknown id and for the in-progress ride: an active
 * session is not history yet. The UI depends on this interface so the detail
 * ViewModel is testable in plain JVM with an in-memory fake.
 */
interface RideDetailSource {
    suspend fun getRideDetail(rideId: String): RideDetail?
}
