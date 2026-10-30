package com.buzzbike.app.domain.export

import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.RideParameters
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

/**
 * Ride input for [GpxWriter].
 *
 * [points] are expected in ascending `timestampMonotonicNanos` order, as
 * delivered by the track-point DAO. The writer tolerates invalid coordinates
 * by skipping those points; it does not reorder them.
 */
data class GpxRide(
    val name: String,
    val startTimeMillis: Long,
    val points: List<TrackPoint>,
)

/**
 * Writes GPX 1.1 with Garmin TrackPointExtension v2.
 *
 * Pure JVM: no Android imports. Output is deterministic, UTF-8, two-space
 * indented and uses `\n` line endings.
 *
 * ### Wall-clock handoff
 *
 * [TrackPoint.timestampMonotonicNanos] is monotonic (`elapsedRealtimeNanos`),
 * not wall-clock. The GPX `<time>` of every point is reconstructed from the
 * ride start wall clock plus the offset from the first valid point:
 * `startTimeMillis + (pointNanos - firstPointNanos) / 1_000_000`. This closes
 * the handoff §5.5 (monotonic sensor clock -> exportable xsd:dateTime).
 *
 * ### Segment cuts
 *
 * Consecutive points more than [RideParameters.GAP_CUT_SECONDS] apart start a
 * new `<trkseg>` (ADR-005 §2: more than one minute without a fix is a sampling
 * discontinuity). Exactly 60 s is *not* a cut; 60.001 s is.
 */
object GpxWriter {
    const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    const val TRACK_POINT_EXTENSION_NAMESPACE =
        "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
    const val CREATOR = "BuzzBike"

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val TIMESTAMP_PATTERN = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'"
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val GAP_CUT_NANOS = RideParameters.GAP_CUT_SECONDS * NANOS_PER_SECOND
    private const val LAT_LON_DECIMALS = 7
    private const val ELEVATION_DECIMALS = 3
    private const val SPEED_DECIMALS = 3
    private const val COURSE_DECIMALS = 2
    private const val MIN_LAT = -90.0
    private const val MAX_LAT = 90.0
    private const val MIN_LON = -180.0
    private const val MAX_LON_EXCLUSIVE = 180.0
    private const val FULL_CIRCLE_DEGREES = 360f
    private const val COURSE_ROUNDING_FACTOR = 100.0
    private const val XML_TAB = 0x9
    private const val XML_LINE_FEED = 0xA
    private const val XML_CARRIAGE_RETURN = 0xD
    private const val XML_CHAR_MIN = 0x20
    private const val XML_CHAR_MAX = 0xD7FF
    private const val XML_NONCHARACTER_MIN = 0xE000
    private const val XML_NONCHARACTER_MAX = 0xFFFD
    private const val XML_ASTRAL_MIN = 0x10000
    private const val XML_ASTRAL_MAX = 0x10FFFF

    private val XML_LEGAL_CODE_POINT_RANGES =
        listOf(
            XML_TAB..XML_TAB,
            XML_LINE_FEED..XML_LINE_FEED,
            XML_CARRIAGE_RETURN..XML_CARRIAGE_RETURN,
            XML_CHAR_MIN..XML_CHAR_MAX,
            XML_NONCHARACTER_MIN..XML_NONCHARACTER_MAX,
            XML_ASTRAL_MIN..XML_ASTRAL_MAX,
        )

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Reconstructs wall-clock milliseconds from the ride start and the
     * monotonic point clock. [firstPointNanos] is the timestamp of the first
     * valid point and acts as the origin.
     */
    fun wallClockMillis(
        startTimeMillis: Long,
        firstPointNanos: Long,
        pointNanos: Long,
    ): Long = startTimeMillis + (pointNanos - firstPointNanos) / NANOS_PER_MILLI

    /**
     * Serializes [ride] as a GPX 1.1 document.
     *
     * Never throws for empty or invalid data: with no valid points it emits
     * metadata and an empty `<trk>` without `<trkseg>`.
     */
    fun write(ride: GpxRide): String {
        val points =
            ride.points.filter { point ->
                // NaN and infinities fail both range checks, so no separate
                // isFinite() test is needed. GPX 1.1 longitudeType is
                // minInclusive=-180 / maxExclusive=180, so +180° is invalid.
                point.lat in MIN_LAT..MAX_LAT &&
                    point.lon >= MIN_LON &&
                    point.lon < MAX_LON_EXCLUSIVE
            }
        return buildString {
            append(XML_DECLARATION).append('\n')
            append("<gpx version=\"1.1\" creator=\"")
                .append(CREATOR)
                .append("\" xmlns=\"")
                .append(GPX_NAMESPACE)
                .append("\" xmlns:gpxtpx=\"")
                .append(TRACK_POINT_EXTENSION_NAMESPACE)
                .append("\">\n")
            appendMetadata(ride)
            appendTrack(ride, points)
            append("</gpx>\n")
        }
    }

    private fun splitIntoSegments(points: List<TrackPoint>): List<List<TrackPoint>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var current = mutableListOf<TrackPoint>()
        points.forEachIndexed { index, point ->
            val gapNanos =
                if (index > 0) point.timestampMonotonicNanos - points[index - 1].timestampMonotonicNanos else 0L
            if (gapNanos > GAP_CUT_NANOS) {
                segments += current
                current = mutableListOf()
            }
            current += point
        }
        segments += current
        return segments
    }

    private fun StringBuilder.appendMetadata(ride: GpxRide) {
        append("  <metadata>\n")
        append("    <name>").append(escapeXml(ride.name)).append("</name>\n")
        append("    <time>").append(formatTime(ride.startTimeMillis)).append("</time>\n")
        append("  </metadata>\n")
    }

    private fun StringBuilder.appendTrack(
        ride: GpxRide,
        points: List<TrackPoint>,
    ) {
        append("  <trk>\n")
        append("    <name>").append(escapeXml(ride.name)).append("</name>\n")
        append("    <type>cycling</type>\n")
        val segments = splitIntoSegments(points)
        if (segments.isNotEmpty()) {
            val originNanos = points.first().timestampMonotonicNanos
            for (segment in segments) {
                append("    <trkseg>\n")
                for (point in segment) {
                    appendTrackPoint(point, ride.startTimeMillis, originNanos)
                }
                append("    </trkseg>\n")
            }
        }
        append("  </trk>\n")
    }

    private fun StringBuilder.appendTrackPoint(
        point: TrackPoint,
        startTimeMillis: Long,
        originNanos: Long,
    ) {
        append("      <trkpt lat=\"")
            .append(formatDecimal(point.lat, LAT_LON_DECIMALS))
            .append("\" lon=\"")
            .append(formatDecimal(point.lon, LAT_LON_DECIMALS))
            .append("\">\n")
        point.altitudeMslM?.takeIf { it.isFinite() }?.let { altitude ->
            append("        <ele>")
                .append(formatDecimal(altitude, ELEVATION_DECIMALS))
                .append("</ele>\n")
        }
        val wallClock = wallClockMillis(startTimeMillis, originNanos, point.timestampMonotonicNanos)
        append("        <time>").append(formatTime(wallClock)).append("</time>\n")
        appendExtensions(point)
        append("      </trkpt>\n")
    }

    private fun StringBuilder.appendExtensions(point: TrackPoint) {
        val speed = point.speedMps?.takeIf { it.isFinite() }
        val course =
            point.bearing?.takeIf { it.isFinite() }?.let { bearing ->
                // Normalize into [0, 360), rounding first so 359.998 becomes
                // 0.00 instead of the out-of-range 360.00.
                val normalized = (bearing % FULL_CIRCLE_DEGREES + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
                val rounded = round(normalized.toDouble() * COURSE_ROUNDING_FACTOR) / COURSE_ROUNDING_FACTOR
                if (rounded >= FULL_CIRCLE_DEGREES.toDouble()) 0.0 else rounded
            }
        if (speed == null && course == null) return
        append("        <extensions>\n")
        append("          <gpxtpx:TrackPointExtension>\n")
        if (speed != null) {
            append("            <gpxtpx:speed>")
                .append(formatDecimal(speed, SPEED_DECIMALS))
                .append("</gpxtpx:speed>\n")
        }
        if (course != null) {
            append("            <gpxtpx:course>")
                .append(formatDecimal(course, COURSE_DECIMALS))
                .append("</gpxtpx:course>\n")
        }
        append("          </gpxtpx:TrackPointExtension>\n")
        append("        </extensions>\n")
    }

    private fun formatTime(millis: Long): String = timestampFormatter.format(Instant.ofEpochMilli(millis))

    private fun formatDecimal(
        value: Double,
        decimals: Int,
    ): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    /**
     * Escapes the XML markup characters and drops code points outside the
     * XML 1.0 `Char` production: `0x9 | 0xA | 0xD | 0x20..0xD7FF |
     * 0xE000..0xFFFD | 0x10000..0x10FFFF`. Lone surrogates fall outside and
     * are dropped; valid surrogate pairs surface as a single code point.
     */
    private fun escapeXml(value: String): String =
        buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                index += Character.charCount(codePoint)
                when (codePoint) {
                    '&'.code -> append("&amp;")
                    '<'.code -> append("&lt;")
                    '>'.code -> append("&gt;")
                    '"'.code -> append("&quot;")
                    '\''.code -> append("&apos;")
                    else ->
                        if (XML_LEGAL_CODE_POINT_RANGES.any { codePoint in it }) {
                            appendCodePoint(codePoint)
                        }
                }
            }
        }
}
