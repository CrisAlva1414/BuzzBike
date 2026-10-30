package com.buzzbike.app.data.export

import android.content.Context
import android.net.Uri
import com.buzzbike.app.domain.export.GpxExporter
import com.buzzbike.app.domain.export.GpxRide
import com.buzzbike.app.domain.export.GpxWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the GPX document through the Android [android.content.ContentResolver].
 *
 * Serialization with [GpxWriter] and the write itself both run inside
 * [Dispatchers.IO], so the CPU-bound XML build never touches the main thread.
 *
 * The destination is parsed as a URI and must use the `content://` scheme (the
 * Storage Access Framework). Other schemes are rejected before touching the
 * resolver, so a caller cannot redirect the export to an arbitrary file path.
 * Mode `"wt"` truncates and writes text in one open, so re-exporting the same
 * destination replaces the previous file instead of appending to it.
 *
 * Failure keeps its cause inside [Result.failure] for the ViewModel, but this
 * class never logs the destination, the coordinates or the XML: a trace is
 * sensitive location data (AGENTS.md §7).
 */
@Singleton
class AndroidGpxExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GpxExporter {
        override suspend fun export(
            destination: String,
            ride: GpxRide,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(destination)
                    require(uri.scheme == CONTENT_SCHEME) { "unsupported scheme" }
                    val xml = GpxWriter.write(ride)
                    val stream =
                        context.contentResolver.openOutputStream(uri, WRITE_TRUNCATE_MODE)
                            ?: error("No output stream for the GPX destination")
                    stream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                }
            }

        private companion object {
            const val WRITE_TRUNCATE_MODE = "wt"
            const val CONTENT_SCHEME = "content"
        }
    }
