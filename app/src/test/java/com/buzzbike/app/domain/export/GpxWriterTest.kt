package com.buzzbike.app.domain.export

import com.buzzbike.app.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM-pure tests for [GpxWriter].
 *
 * The writer has no Android dependencies, so the XML contract is verified with
 * the JDK XML parser and plain string assertions.
 */
class GpxWriterTest {
    private fun trackPoint(
        nanos: Long,
        altitude: Double? = null,
        speed: Double? = null,
        bearing: Float? = null,
    ): TrackPoint =
        TrackPoint(
            timestampMonotonicNanos = nanos,
            lat = VALID_LAT,
            lon = VALID_LON,
            altitudeMslM = altitude,
            speedMps = speed,
            bearing = bearing,
        )

    @Test
    fun given_pointWithEveryField_when_written_then_childrenFollowGpxOrder() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Morning ride",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(
                                nanos = FIRST_NANOS,
                                altitude = ALTITUDE_M,
                                speed = SPEED_MPS,
                                bearing = BEARING_DEGREES,
                            ),
                        ),
                ),
            )

        val eleIndex = xml.indexOf("<ele>")
        val timeIndex = xml.indexOf("<time>", eleIndex)
        val extensionsIndex = xml.indexOf("<extensions>")
        val speedIndex = xml.indexOf("<gpxtpx:speed>")
        val courseIndex = xml.indexOf("<gpxtpx:course>")

        assertTrue(eleIndex >= 0)
        assertTrue(eleIndex < timeIndex)
        assertTrue(timeIndex < extensionsIndex)
        assertTrue(extensionsIndex < speedIndex)
        assertTrue(speedIndex < courseIndex)
    }

    @Test
    fun given_fullDocument_when_written_then_itIsWellFormedXml() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "GPX check",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(
                                nanos = FIRST_NANOS,
                                altitude = ALTITUDE_M,
                                speed = SPEED_MPS,
                                bearing = BEARING_DEGREES,
                            ),
                        ),
                ),
            )

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        assertEquals("gpx", document.documentElement.tagName)
        assertEquals(GpxWriter.GPX_NAMESPACE, document.documentElement.namespaceURI)
    }

    @Test
    fun given_specialAndControlCharacters_when_written_then_nameIsEscapedAndControlRemoved() {
        val name = "A & B < C > D \" E ' F\u0001"
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = name,
                    startTimeMillis = START_MILLIS,
                    points = emptyList(),
                ),
            )

        assertTrue(xml.contains("A &amp; B &lt; C &gt; D &quot; E &apos; F"))
        assertFalse(xml.contains('\u0001'))
    }

    @Test
    fun given_twoPoints_when_written_then_wallClockUsesFirstPointAsOrigin() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Clock",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(nanos = FIRST_NANOS),
                            trackPoint(nanos = FIRST_NANOS + FIFTEEN_HUNDRED_MILLIS * NANOS_PER_MILLI),
                        ),
                ),
            )

        assertTrue(xml.contains("<time>${formatTime(START_MILLIS)}</time>"))
        assertTrue(xml.contains("<time>${formatTime(START_MILLIS + FIFTEEN_HUNDRED_MILLIS)}</time>"))
    }

    @Test
    fun given_gaps_when_written_then_trksegCutsOnlyAboveSixtySeconds() {
        val cutXml =
            GpxWriter.write(
                GpxRide(
                    name = "Cut",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(nanos = FIRST_NANOS),
                            trackPoint(nanos = FIRST_NANOS + GAP_30_SECONDS * NANOS_PER_SECOND),
                            trackPoint(nanos = FIRST_NANOS + (GAP_30_SECONDS + GAP_91_SECONDS) * NANOS_PER_SECOND),
                        ),
                ),
            )
        val exactXml =
            GpxWriter.write(
                GpxRide(
                    name = "Exact",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(nanos = FIRST_NANOS),
                            trackPoint(nanos = FIRST_NANOS + GAP_60_SECONDS * NANOS_PER_SECOND),
                        ),
                ),
            )

        assertEquals(2, cutXml.split("<trkseg>").size - 1)
        assertEquals(1, exactXml.split("<trkseg>").size - 1)
    }

    @Test
    fun given_pointWithoutAltitudeSpeedOrBearing_when_written_then_eleAndExtensionsAreOmitted() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Bare",
                    startTimeMillis = START_MILLIS,
                    points = listOf(trackPoint(nanos = FIRST_NANOS)),
                ),
            )

        assertTrue(xml.contains("<trkpt lat=\""))
        assertTrue(xml.contains("<time>"))
        assertFalse(xml.contains("<ele>"))
        assertFalse(xml.contains("<extensions>"))
    }

    @Test
    fun given_invalidCoordinates_when_written_then_thosePointsAreSkipped() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Invalid",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            TrackPoint(
                                timestampMonotonicNanos = FIRST_NANOS,
                                lat = Double.NaN,
                                lon = VALID_LON,
                            ),
                            TrackPoint(
                                timestampMonotonicNanos = FIRST_NANOS + NANOS_PER_SECOND,
                                lat = LAT_OUT_OF_RANGE,
                                lon = VALID_LON,
                            ),
                            trackPoint(nanos = FIRST_NANOS + 2 * NANOS_PER_SECOND),
                        ),
                ),
            )

        assertEquals(1, xml.split("<trkpt ").size - 1)
        assertFalse(xml.contains("NaN"))
        assertFalse(xml.contains("lat=\"$LAT_OUT_OF_RANGE"))
    }

    @Test
    fun given_bearingOfFullCircle_when_written_then_courseIsNormalizedToZero() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Course",
                    startTimeMillis = START_MILLIS,
                    points = listOf(trackPoint(nanos = FIRST_NANOS, bearing = FULL_CIRCLE_DEGREES)),
                ),
            )

        assertTrue(xml.contains("<gpxtpx:course>0.00</gpxtpx:course>"))
    }

    @Test
    fun given_emptyPointList_when_written_then_documentIsValidWithoutTrkseg() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Empty",
                    startTimeMillis = START_MILLIS,
                    points = emptyList(),
                ),
            )

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        assertEquals("gpx", document.documentElement.tagName)
        assertTrue(xml.contains("<trk>"))
        assertTrue(xml.contains("</trk>"))
        assertTrue(xml.contains("<type>cycling</type>"))
        assertFalse(xml.contains("<trkseg>"))
    }

    @Test
    fun given_numericFields_when_written_then_theyUseRootLocaleDecimalPoints() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Numbers",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            trackPoint(
                                nanos = FIRST_NANOS,
                                altitude = ALTITUDE_M,
                                speed = SPEED_MPS,
                                bearing = BEARING_DEGREES,
                            ),
                        ),
                ),
            )

        assertTrue(xml.contains("lat=\"-33.4489000\""))
        assertTrue(xml.contains("lon=\"-70.6693000\""))
        assertTrue(xml.contains("<ele>540.500</ele>"))
        assertTrue(xml.contains("<gpxtpx:speed>6.250</gpxtpx:speed>"))
        assertTrue(xml.contains("<gpxtpx:course>45.00</gpxtpx:course>"))
        assertFalse(Regex("\\d,\\d").containsMatchIn(xml))
    }

    @Test
    fun given_documentRoot_when_written_then_declaresGpx11AndGpxtpxV2WithoutXsi() {
        val xml = GpxWriter.write(GpxRide("Root", START_MILLIS, emptyList()))

        assertTrue(xml.contains("version=\"1.1\""))
        assertTrue(xml.contains("creator=\"${GpxWriter.CREATOR}\""))
        assertTrue(xml.contains("xmlns=\"${GpxWriter.GPX_NAMESPACE}\""))
        assertTrue(
            xml.contains("xmlns:gpxtpx=\"${GpxWriter.TRACK_POINT_EXTENSION_NAMESPACE}\""),
        )
        assertTrue(GpxWriter.TRACK_POINT_EXTENSION_NAMESPACE.endsWith("/v2"))
        assertFalse(xml.contains("xsi"))
    }

    @Test
    fun given_negativeBearing_when_written_then_courseIsNormalizedIntoRange() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Negative",
                    startTimeMillis = START_MILLIS,
                    points = listOf(trackPoint(nanos = FIRST_NANOS, bearing = -45f)),
                ),
            )

        assertTrue(xml.contains("<gpxtpx:course>315.00</gpxtpx:course>"))
    }

    @Test
    fun given_bearingJustBelowFullCircle_when_written_then_courseStaysBelowThreeSixty() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Almost",
                    startTimeMillis = START_MILLIS,
                    points = listOf(trackPoint(nanos = FIRST_NANOS, bearing = 359.998f)),
                ),
            )

        val course =
            Regex("<gpxtpx:course>([^<]*)</gpxtpx:course>")
                .find(xml)!!
                .groupValues[1]
                .toDouble()

        // Contract: course is in [0, 360). 359.998 must not round to 360.00.
        assertTrue("course=$course must be < 360", course < 360.0)
    }

    @Test
    fun given_firstPointInvalid_when_written_then_clockOriginIsFirstValidPoint() {
        val xml =
            GpxWriter.write(
                GpxRide(
                    name = "Origin",
                    startTimeMillis = START_MILLIS,
                    points =
                        listOf(
                            TrackPoint(
                                timestampMonotonicNanos = FIRST_NANOS,
                                lat = Double.NaN,
                                lon = VALID_LON,
                            ),
                            trackPoint(nanos = FIRST_NANOS + 10 * NANOS_PER_SECOND),
                        ),
                ),
            )

        // The single valid point is 10 s after the invalid one, but it is the
        // origin, so its wall clock equals the ride start.
        assertEquals(1, xml.split("<trkpt ").size - 1)
        assertTrue(xml.contains("<time>${formatTime(START_MILLIS)}</time>"))
        assertFalse(xml.contains("<time>${formatTime(START_MILLIS + 10_000)}</time>"))
    }

    @Test
    fun given_nameWithLineBreak_when_written_then_documentStillParses() {
        val xml =
            GpxWriter.write(
                GpxRide(name = "line1\nline2", startTimeMillis = START_MILLIS, points = emptyList()),
            )

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        assertEquals("gpx", document.documentElement.tagName)
    }

    @Test
    fun given_nonC0IllegalXmlCharacters_when_written_then_documentStillParses() {
        for (illegal in listOf("\uFFFF", "\uFFFE", "\uD800")) {
            val xml =
                GpxWriter.write(
                    GpxRide(
                        name = "bad${illegal}name",
                        startTimeMillis = START_MILLIS,
                        points = emptyList(),
                    ),
                )
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // XML 1.0 forbids U+FFFE, U+FFFF and lone surrogates; the writer
            // must strip them like it strips C0 controls.
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }
    }

    private fun formatTime(millis: Long): String = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(millis))

    private companion object {
        private const val VALID_LAT = -33.4489
        private const val VALID_LON = -70.6693
        private const val START_MILLIS = 1_700_000_000_000L
        private const val FIRST_NANOS = 1_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val ALTITUDE_M = 540.5
        private const val SPEED_MPS = 6.25
        private const val BEARING_DEGREES = 45f
        private const val FULL_CIRCLE_DEGREES = 360f
        private const val FIFTEEN_HUNDRED_MILLIS = 1_500L
        private const val GAP_30_SECONDS = 30L
        private const val GAP_60_SECONDS = 60L
        private const val GAP_91_SECONDS = 91L
        private const val LAT_OUT_OF_RANGE = 91.0
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
    }
}
