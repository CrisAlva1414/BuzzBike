package com.buzzbike.app.ui.garage

import com.buzzbike.app.domain.wear.MAX_GARAGE_NOTES_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the service-note bound shared with the repository. */
class GarageServiceFormRulesTest {
    @Test
    fun given_notesAtLimit_when_serviceNotesError_thenNull() {
        assertNull(serviceNotesError("x".repeat(MAX_GARAGE_NOTES_LENGTH)))
    }

    @Test
    fun given_notesOverLimit_when_serviceNotesError_thenSpecificMessage() {
        assertEquals(
            "Máximo $MAX_GARAGE_NOTES_LENGTH caracteres",
            serviceNotesError("x".repeat(MAX_GARAGE_NOTES_LENGTH + 1)),
        )
    }

    @Test
    fun given_surroundingWhitespace_when_serviceNotesError_thenTrimmedLengthIsUsed() {
        assertNull(serviceNotesError("  ${"x".repeat(MAX_GARAGE_NOTES_LENGTH)}  "))
    }

    @Test
    fun given_overlongNotes_when_validateServiceForm_thenNotesErrorIsSet() {
        val validated = validateServiceForm(serviceForm("x".repeat(MAX_GARAGE_NOTES_LENGTH + 1)))

        assertEquals(SERVICE_NOTES_TOO_LONG_MESSAGE, validated.form.notesError)
    }

    private fun serviceForm(notes: String) =
        GarageForm.Service(
            componentId = "chain",
            componentLabel = "Cadena",
            km = "",
            hours = "",
            notes = notes,
            wearRatio = null,
        )
}
