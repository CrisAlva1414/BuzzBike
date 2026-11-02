package com.buzzbike.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BuzzComponentsTest {
    @Test
    fun metricCellDescription_includesLabelAndValueExactlyOnce() {
        val description = metricCellDescription(label = "Distancia", value = "12,35 km")

        assertEquals("Distancia: 12,35 km", description)
        assertEquals(1, description.countOccurrences("Distancia"))
        assertEquals(1, description.countOccurrences("12,35 km"))
    }

    @Test
    fun metricCellDescription_doesNotRepeatVisibleText() {
        val label = "Velocidad media"
        val value = "18,4 km/h"

        val description = metricCellDescription(label, value)

        // The cell's two visible Texts are the label and the value; reading the
        // container must not announce either of them a second time.
        assertEquals(1, description.countOccurrences(label))
        assertEquals(1, description.countOccurrences(value))
        assertEquals("$label: $value", description)
    }

    @Test
    fun statusBannerMessage_addsTonePrefixExactlyOnce() {
        val text = "Mapa no disponible sin conexión"

        val warning = statusBannerMessage(text, StatusTone.WARNING)
        val danger = statusBannerMessage(text, StatusTone.DANGER)
        val info = statusBannerMessage(text, StatusTone.INFO)

        assertEquals("AVISO: $text", warning)
        assertEquals("ATENCIÓN: $text", danger)
        assertEquals("INFO: $text", info)
        assertEquals(1, warning.countOccurrences("AVISO"))
        assertEquals(1, danger.countOccurrences("ATENCIÓN"))
        assertEquals(1, info.countOccurrences("INFO"))
    }

    @Test
    fun statusBannerMessage_doesNotDuplicateBodyText() {
        val text = "Se perdió el GPS"
        val message = statusBannerMessage(text, StatusTone.DANGER)

        // Visible body text also travels to semantics; the prefix must not be
        // repeated and the body must appear exactly once.
        assertEquals(1, message.countOccurrences(text))
        assertFalse(message.startsWith("ATENCIÓN: ATENCIÓN"))
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
