package com.buzzbike.app.ui.history.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.buzzbike.app.domain.history.GeoPoint
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.ui.components.BuzzButton
import com.buzzbike.app.ui.components.StatusBanner
import com.buzzbike.app.ui.components.StatusTone
import com.buzzbike.app.ui.theme.BuzzDimens
import com.buzzbike.app.ui.theme.BuzzTheme
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * OpenFreeMap's Liberty style. No API key and no other account is required;
 * the vector tiles are fetched anonymously by MapLibre.
 *
 * Privacy note: fetching tiles tells OpenFreeMap (the tile host) the
 * approximate viewport/zoom needed to render this ride, plus the usual HTTP
 * metadata. The GPS track itself never leaves the device: it is rendered
 * locally as a GeoJSON source and is not part of any tile request.
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val TRACK_SOURCE_ID = "buzzbike-track"
private const val TRACK_LAYER_ID = "buzzbike-track-line"
private const val TRACK_LINE_WIDTH = 5f
private const val MAP_PADDING_PX = 56
private const val SINGLE_POINT_ZOOM = 14.0
private const val CONTENT_PADDING_DP = 16
private const val PANEL_GAP_DP = 12
private const val NO_TRACK_TEXT = "Sin recorrido para mostrar"
private const val OFFLINE_TEXT = "Mapa no disponible sin conexión"
private const val MAP_AVAILABLE_TEXT = "Mapa disponible"
private const val RETRY_LABEL = "REINTENTAR"

/**
 * How long to wait for `Style.OnStyleLoaded` before treating the map as
 * failed. MapLibre queues style/tile requests when the network is unreachable
 * instead of emitting an error, so without this timeout the offline notice and
 * its retry action would never become reachable.
 */
private const val STYLE_LOAD_TIMEOUT_MS = 9_000L

private const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
private const val OPENMAPTILES_URL = "https://www.openmaptiles.org/"
private const val OPENFREEMAP_URL = "https://openfreemap.org/"
private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
private const val OPENMAPTILES_ATTRIBUTION = "© OpenMapTiles"
private const val OPENFREEMAP_ATTRIBUTION = "OpenFreeMap"
private const val ATTRIBUTION_SEPARATOR = " · "
private const val ATTRIBUTION_PADDING_DP = 8
private const val ATTRIBUTION_CORNER_DP = 4

/**
 * Map of a completed ride's trace.
 *
 * The map is only composed when the user opens the MAP tab, so opening the
 * detail screen does not download tiles. A `MapView` is created per attempt
 * and its full lifecycle is forwarded from [LocalLifecycleOwner]; switching
 * back to DATOS or leaving the screen disposes and destroys it.
 *
 * Connectivity is checked with `ConnectivityManager` before the `MapView` is
 * composed. Without a validated internet connection the state is [RideMapUiState.Failed]
 * immediately and MapLibre is never instantiated (offline it would queue
 * requests and stay blank forever). A style-load timeout backs up the same
 * path if the connection drops or the style host stalls. When the network
 * returns, REINTENTAR recreates the map with a fresh attempt id.
 *
 * Attribution is always visible: this composable draws its own overlay with
 * OpenStreetMap, OpenMapTiles and OpenFreeMap credits, and MapLibre's own
 * attribution is kept enabled (this screen never calls
 * `setAttributionEnabled(false)`).
 *
 * Privacy: tile requests reveal the approximate viewport to OpenFreeMap, but
 * the recorded GPS track never leaves the device.
 */
@Composable
fun RideMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = BuzzTheme.colors
    val trackColor = colors.ink.toArgb()
    val isOnline by rememberIsOnline()
    var state by remember(points) { mutableStateOf(mapStateFor(points, isOnline)) }
    var announcement by remember(points) { mutableStateOf(RideMapAnnouncement.initial(state)) }

    // If connectivity drops while the map is loading, stop waiting on MapLibre
    // and surface the offline notice. The map stays failed until REINTENTAR.
    LaunchedEffect(isOnline) {
        if (!isOnline) state = state.onFailure()
    }

    // Accessible recovery announcement: a live region only receives text after
    // OnStyleLoaded, so TalkBack is never told the map is back while a retry is
    // still loading or after it failed again.
    LaunchedEffect(state) {
        announcement = announcement.onMapState(state)
    }

    Box(
        modifier =
            modifier
                .background(colors.panel)
                .border(BuzzDimens.Border, colors.ink),
    ) {
        when (val current = state) {
            RideMapUiState.NoTrack -> MapMessage(text = NO_TRACK_TEXT)
            is RideMapUiState.Loading ->
                if (isOnline) {
                    key(current.attempt) {
                        RideMapCanvas(
                            points = current.points,
                            geoJson = current.geoJson,
                            lineColor = trackColor,
                            onFailure = { state = state.onFailure() },
                            onStyleLoaded = { announcement = announcement.onStyleLoaded() },
                        )
                    }
                } else {
                    // Defensive: retry raced with a connectivity change; never
                    // instantiate MapLibre while offline.
                    MapOffline(onRetry = { state = state.onFailure() })
                }
            is RideMapUiState.Failed ->
                MapOffline(
                    onRetry = {
                        state = if (isOnline) state.onRetry() else state.onFailure()
                    },
                )
        }
        MapAttribution(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(ATTRIBUTION_PADDING_DP.dp),
        )
        if (announcement.announceRecovered) {
            MapRecoveredAnnouncement()
        }
    }
}

/**
 * Small live-region-only node announced when the map recovers.
 *
 * It is deliberately 1 dp and not focusable: it exists only so TalkBack gets a
 * polite live-region update. The offline case is announced by the visible
 * [StatusBanner] in [MapOffline].
 */
@Composable
private fun MapRecoveredAnnouncement() {
    Box(
        modifier =
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = MAP_AVAILABLE_TEXT
                },
    )
}

@Composable
private fun MapMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(CONTENT_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = BuzzTheme.colors.ink,
        )
    }
}

@Composable
private fun MapOffline(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(CONTENT_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp, Alignment.CenterVertically),
    ) {
        StatusBanner(
            text = OFFLINE_TEXT,
            tone = StatusTone.WARNING,
            modifier = Modifier.fillMaxWidth(),
            announceChanges = true,
        )
        BuzzButton(
            text = RETRY_LABEL,
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Always-visible attribution required by ADR-002.
 *
 * MapLibre's own attribution button is kept enabled, but it hides the credits
 * behind an "(i)" tap. This overlay keeps the OpenStreetMap/OpenMapTiles
 * credits readable over the map, on a translucent panel so it does not depend
 * on the underlying tiles being light. It sits bottom-start and is intentionally
 * small so it does not cover the rendered track.
 */
@Composable
private fun MapAttribution(modifier: Modifier = Modifier) {
    val colors = BuzzTheme.colors
    val linkStyle =
        TextLinkStyles(
            style = SpanStyle(color = colors.ink, textDecoration = TextDecoration.Underline),
        )
    val text =
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(OSM_COPYRIGHT_URL, linkStyle)) { append(OSM_ATTRIBUTION) }
            append(ATTRIBUTION_SEPARATOR)
            withLink(LinkAnnotation.Url(OPENMAPTILES_URL, linkStyle)) { append(OPENMAPTILES_ATTRIBUTION) }
            append(ATTRIBUTION_SEPARATOR)
            withLink(LinkAnnotation.Url(OPENFREEMAP_URL, linkStyle)) { append(OPENFREEMAP_ATTRIBUTION) }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.ink,
        modifier =
            modifier
                // Fully opaque panel: the credits must pass contrast on top of
                // any tile, in both the Sol and Noche palettes.
                .background(
                    color = colors.panel,
                    shape = RoundedCornerShape(ATTRIBUTION_CORNER_DP.dp),
                ).padding(horizontal = ATTRIBUTION_PADDING_DP.dp, vertical = ATTRIBUTION_PADDING_DP.dp / 2),
    )
}

/**
 * Observes whether the device has an active, validated internet connection.
 *
 * `NET_CAPABILITY_VALIDATED` is required (not just `INTERNET`) because a
 * captive portal or an airplane-mode Wi-Fi association can advertise INTERNET
 * while no request can actually complete. The initial value is read
 * synchronously so the first composition already knows whether to instantiate
 * MapLibre.
 */
@Composable
private fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = context.hasValidatedInternet()) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        value = context.hasValidatedInternet()
                    }

                    override fun onLost(network: Network) {
                        value = context.hasValidatedInternet()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        value = context.hasValidatedInternet()
                    }
                }
            connectivity.registerDefaultNetworkCallback(callback)
            value = context.hasValidatedInternet()
            awaitDispose { connectivity.unregisterNetworkCallback(callback) }
        } else {
            value = false
        }
    }
}

private fun Context.hasValidatedInternet(): Boolean {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val capabilities =
        connectivity
            ?.activeNetwork
            ?.let { network -> connectivity.getNetworkCapabilities(network) }
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
private fun RideMapCanvas(
    points: List<GeoPoint>,
    geoJson: String,
    lineColor: Int,
    onFailure: () -> Unit,
    onStyleLoaded: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnStyleLoaded by rememberUpdatedState(onStyleLoaded)
    var styleLoaded by remember { mutableStateOf(false) }
    val mapView =
        remember(context) {
            // MapView throws unless MapLibre is initialized first. The OpenGL
            // renderer is selected explicitly because this module uses
            // `android-sdk-opengl`. Initialization is idempotent.
            MapLibre.getInstance(
                context.applicationContext,
                null,
                WellKnownTileServer.MapLibre,
                RenderingEngine.Type.OPENGL,
            )
            MapView(context).apply { onCreate(null) }
        }

    // MapLibre queues style requests instead of failing when the network is
    // unreachable; without this timeout the map would stay blank forever and
    // the retry action would never be shown.
    LaunchedEffect(mapView) {
        delay(STYLE_LOAD_TIMEOUT_MS)
        if (!styleLoaded) currentOnFailure()
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(mapView) {
        val failureListener = MapView.OnDidFailLoadingMapListener { currentOnFailure() }
        mapView.addOnDidFailLoadingMapListener(failureListener)
        mapView.getMapAsync { map ->
            // Attribution must remain visible; the default is true and it is
            // never disabled (OpenStreetMap/OpenMapTiles require it).
            map.uiSettings.isAttributionEnabled = true
            map.setStyle(STYLE_URL) { style ->
                styleLoaded = true
                renderTrack(map = map, style = style, points = points, geoJson = geoJson, lineColor = lineColor)
                currentOnStyleLoaded()
            }
        }
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failureListener)
            if (!mapView.isDestroyed) {
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Adds the track source/layer idempotently.
 *
 * `setStyle` invokes this again after a style reload; checking the existing
 * source/layer avoids duplicate-id exceptions and keeps the geometry current.
 */
private fun renderTrack(
    map: MapLibreMap,
    style: Style,
    points: List<GeoPoint>,
    geoJson: String,
    lineColor: Int,
) {
    val source = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)
    if (source == null) {
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID, geoJson))
    } else {
        source.setGeoJson(geoJson)
    }
    if (style.getLayer(TRACK_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                lineColor(lineColor),
                lineWidth(TRACK_LINE_WIDTH),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
    val bounds = boundsOf(points)
    if (bounds.isEmptySpan) {
        val first = points.first()
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lon), SINGLE_POINT_ZOOM))
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, MAP_PADDING_PX))
    }
}

private fun boundsOf(points: List<GeoPoint>): LatLngBounds {
    val builder = LatLngBounds.Builder()
    points.forEach { point -> builder.include(LatLng(point.lat, point.lon)) }
    return builder.build()
}
