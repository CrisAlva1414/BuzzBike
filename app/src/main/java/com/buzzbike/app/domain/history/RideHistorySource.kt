package com.buzzbike.app.domain.history

import kotlinx.coroutines.flow.Flow

/**
 * Read-only port that exposes completed rides to the history UI.
 *
 * The UI depends on this interface instead of the concrete persistence layer,
 * so the history screen stays testable in JVM with an in-memory fake.
 */
interface RideHistorySource {
    fun observeCompletedRides(): Flow<List<RideRecord>>
}
