package com.buzzbike.app.data.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.buzzbike.app.domain.export.GpxRide
import com.buzzbike.app.domain.model.TrackPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * JVM tests for [AndroidGpxExporter] against Robolectric's content resolver.
 *
 * Only `content://` destinations are accepted, so the happy path registers a
 * real [FileOutputStream] on [ShadowContentResolver] for a `content://` URI.
 * That still exercises the production code path: parse, reject non-content
 * schemes, serialize with [GpxWriter], open with `"wt"`, write UTF-8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidGpxExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val exporter = AndroidGpxExporter(context)

    @Test
    fun given_registeredContentUri_when_export_then_xmlIsWrittenAsUtf8() =
        runTest {
            val file = File.createTempFile("buzzbike-", ".gpx")
            val uri = Uri.parse(CONTENT_DESTINATION)
            shadowOf(context.contentResolver).registerOutputStream(uri, FileOutputStream(file))

            val result = exporter.export(uri.toString(), ride)

            assertTrue(result.isSuccess)
            val xml = file.readText(Charsets.UTF_8)
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertTrue(xml.contains("<name>Ride de prueba</name>"))
            assertTrue(xml.contains("<trkpt lat=\"-33.4500000\" lon=\"-70.6600000\">"))
            assertTrue(xml.contains("<trkpt lat=\"-33.4600000\" lon=\"-70.6700000\">"))
            assertTrue(xml.contains("<time>2023-11-14T22:13:20.000Z</time>"))
            file.delete()
        }

    @Test
    fun given_unresolvableDestination_when_export_then_failure() =
        runTest {
            val result = exporter.export(MISSING_DESTINATION, ride)

            assertTrue(result.isFailure)
        }

    @Test
    fun given_fileDestination_when_export_then_failure() =
        runTest {
            val result = exporter.export(FILE_DESTINATION, ride)

            assertTrue(result.isFailure)
        }

    private companion object {
        val ride =
            GpxRide(
                name = "Ride de prueba",
                startTimeMillis = 1_700_000_000_000L,
                points =
                    listOf(
                        TrackPoint(timestampMonotonicNanos = 1_000_000L, lat = -33.45, lon = -70.66),
                        TrackPoint(timestampMonotonicNanos = 2_000_000L, lat = -33.46, lon = -70.67),
                    ),
            )
        const val CONTENT_DESTINATION = "content://com.buzzbike.test/export/ride.gpx"
        const val MISSING_DESTINATION = "content://com.buzzbike.test/missing/ride.gpx"
        const val FILE_DESTINATION = "file:///tmp/buzzbike-test.gpx"
    }
}
