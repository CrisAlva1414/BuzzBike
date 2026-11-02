package com.buzzbike.app.data.repository

import com.buzzbike.app.domain.wear.MAX_GARAGE_NOTES_LENGTH

/**
 * Validation and normalization shared by the garage repositories.
 *
 * This is the last line of defence before user input reaches Room: the UI may
 * change, but an unbounded or invalid string/measurement must never be stored.
 */
internal object ComponentInputValidator {
    /**
     * Rejects negative or non-finite measurements before they reach the event
     * log. [value] is null when the axis is not tracked.
     */
    fun requireValidMeasurement(
        value: Double?,
        field: String,
    ) {
        require(value == null || value.isFinite() && value >= 0.0) {
            "$field must be a non-negative finite number"
        }
    }

    /**
     * Trims a note and rejects it when it exceeds [MAX_GARAGE_NOTES_LENGTH].
     *
     * Blank notes collapse to `null` so the row does not store meaningless
     * whitespace.
     */
    fun normalizeNotes(notes: String?): String? {
        val trimmed = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(trimmed.length <= MAX_GARAGE_NOTES_LENGTH) {
            "notes must be at most $MAX_GARAGE_NOTES_LENGTH characters"
        }
        return trimmed
    }
}
