package com.buzzbike.app.ui.history.map

import com.buzzbike.app.domain.history.GeoPoint
import com.buzzbike.app.domain.history.TrackGeoJson
import com.buzzbike.app.domain.model.TrackPoint

private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0

/**
 * Pure state machine behind the ride map.
 *
 * It is deliberately free of Compose, Android, and MapLibre types so the JVM
 * suite can assert point selection, the `< 2` points case, the failure path
 * and retry without instantiating a `MapView`.
 */
internal sealed interface RideMapUiState {
    /** Fewer than two usable coordinates: there is no line to draw. */
    data object NoTrack : RideMapUiState

    /**
     * A usable GeoJSON document is ready. [attempt] identifies the MapView
     * instance so a retry after a style failure re-creates it instead of
     * reusing a half-initialized renderer.
     */
    data class Loading(
        val points: List<GeoPoint>,
        val geoJson: String,
        val attempt: Int = 0,
    ) : RideMapUiState

    /**
     * The map cannot be shown: there is no validated internet connection, the
     * style failed to load, or the style load timed out. The screen shows the
     * offline notice plus a retry action.
     */
    data class Failed(
        val points: List<GeoPoint>,
        val geoJson: String,
        val attempt: Int = 0,
    ) : RideMapUiState
}

/**
 * Builds the initial state from a persisted trace.
 *
 * Invalid fixes (non-finite or outside WGS84) are discarded before the
 * GeoJSON builder runs, so a corrupt row cannot break the whole map.
 *
 * [isOnline] is the result of checking `ConnectivityManager` for an active
 * network with `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`. When
 * it is false the state is [RideMapUiState.Failed] immediately, so the caller
 * never instantiates MapLibre (which would otherwise queue requests and stay
 * blank indefinitely while offline).
 */
internal fun mapStateFor(
    points: List<TrackPoint>,
    isOnline: Boolean = true,
): RideMapUiState {
    val validPoints = validMapPoints(points)
    val geoJson = TrackGeoJson.lineStringFeatureCollection(validPoints) ?: return RideMapUiState.NoTrack
    return if (isOnline) {
        RideMapUiState.Loading(points = validPoints, geoJson = geoJson)
    } else {
        RideMapUiState.Failed(points = validPoints, geoJson = geoJson)
    }
}

/**
 * Announcement state for the map's polite "Mapa disponible" live region.
 *
 * Recovering must be announced only after the style actually loaded. Moving
 * back to [RideMapUiState.Loading] is not enough: a retry can fail again, and
 * announcing on [RideMapUiState.Loading] would tell TalkBack the map is back
 * before it renders.
 */
internal data class RideMapAnnouncement(
    val sawFailure: Boolean = false,
    val announceRecovered: Boolean = false,
) {
    /** Tracks map-state changes; [RideMapUiState.Loading] never announces. */
    fun onMapState(state: RideMapUiState): RideMapAnnouncement =
        when (state) {
            is RideMapUiState.Failed -> copy(sawFailure = true, announceRecovered = false)
            is RideMapUiState.Loading -> copy(announceRecovered = false)
            RideMapUiState.NoTrack -> RideMapAnnouncement()
        }

    /** Announces a recovery only for a map that failed earlier in this tab. */
    fun onStyleLoaded(): RideMapAnnouncement =
        if (sawFailure) {
            RideMapAnnouncement(sawFailure = false, announceRecovered = true)
        } else {
            this
        }

    companion object {
        /** Initial announcement state for [state]. */
        fun initial(state: RideMapUiState): RideMapAnnouncement =
            RideMapAnnouncement(sawFailure = state is RideMapUiState.Failed)
    }
}

/** Filters a persisted trace down to coordinates a map can safely render. */
internal fun validMapPoints(points: List<TrackPoint>): List<GeoPoint> =
    points.mapNotNull { point ->
        if (point.isRenderable()) {
            GeoPoint(lat = point.lat, lon = point.lon)
        } else {
            null
        }
    }

/**
 * Marks a map whose style did not finish loading within the timeout as failed.
 *
 * MapLibre keeps tile/style requests queued instead of reporting an error when
 * the network is unreachable, so the timeout is what makes the retry path
 * reachable. It reuses [onFailure] because both cases need the same payload.
 */
internal fun RideMapUiState.onStyleTimeout(): RideMapUiState = onFailure()

/** Marks a loading map as failed, preserving the payload needed by a retry. */
internal fun RideMapUiState.onFailure(): RideMapUiState =
    when (this) {
        is RideMapUiState.Loading -> RideMapUiState.Failed(points = points, geoJson = geoJson, attempt = attempt)
        is RideMapUiState.Failed, RideMapUiState.NoTrack -> this
    }

/** Restarts a failed map with a fresh attempt id, or keeps an unaffected state. */
internal fun RideMapUiState.onRetry(): RideMapUiState =
    when (this) {
        is RideMapUiState.Failed -> RideMapUiState.Loading(points = points, geoJson = geoJson, attempt = attempt + 1)
        is RideMapUiState.Loading, RideMapUiState.NoTrack -> this
    }

private fun TrackPoint.isRenderable(): Boolean =
    lat.isFinite() &&
        lon.isFinite() &&
        lat in MIN_LATITUDE..MAX_LATITUDE &&
        lon in MIN_LONGITUDE..MAX_LONGITUDE
