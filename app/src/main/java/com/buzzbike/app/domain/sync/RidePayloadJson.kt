package com.buzzbike.app.domain.sync

/**
 * Minimal, dependency-free JSON encoder for [RideSyncPayload].
 *
 * The payload is output-only: it is written to the sync outbox and sent to
 * Hermes, and the app never parses it back. The encoder is hand-rolled so the
 * project does not add a serialization dependency and so invalid JSON literals
 * (`NaN`, `Infinity`) can never reach the wire: non-finite doubles are encoded
 * as `null`.
 *
 * Output is compact UTF-8 with deterministic field order.
 */
object RidePayloadJson {
    fun encode(payload: RideSyncPayload): ByteArray =
        buildString {
            append('{')
            appendIntField("version", payload.version)
            append(',')
            appendStringField("rideId", payload.rideId)
            append(',')
            appendLongField("startTimeMillis", payload.startTimeMillis)
            append(',')
            append("\"endTimeMillis\":")
            if (payload.endTimeMillis == null) append("null") else append(payload.endTimeMillis)
            append(',')
            appendDoubleField("distanceM", payload.distanceM)
            append(',')
            appendLongField("movingTimeSeconds", payload.movingTimeSeconds)
            append(',')
            appendLongField("stoppedTimeSeconds", payload.stoppedTimeSeconds)
            append(',')
            appendDoubleField("elevationGainM", payload.elevationGainM)
            append(',')
            appendDoubleField("elevationLossM", payload.elevationLossM)
            append(',')
            appendNullableDoubleField("caloriesKcal", payload.caloriesKcal)
            append(',')
            appendDoubleField("avgSpeedMps", payload.avgSpeedMps)
            append(',')
            appendDoubleField("maxSpeedMps", payload.maxSpeedMps)
            append(',')
            append("\"waypoints\":[")
            payload.waypoints.forEachIndexed { index, waypoint ->
                if (index > 0) append(',')
                appendWaypoint(waypoint)
            }
            append(']')
            append('}')
        }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendIntField(
        name: String,
        value: Int,
    ) {
        append('"').append(name).append("\":").append(value)
    }

    private fun StringBuilder.appendLongField(
        name: String,
        value: Long,
    ) {
        append('"').append(name).append("\":").append(value)
    }

    private fun StringBuilder.appendDoubleField(
        name: String,
        value: Double,
    ) {
        append('"').append(name).append("\":")
        appendFiniteOrNull(value)
    }

    private fun StringBuilder.appendNullableDoubleField(
        name: String,
        value: Double?,
    ) {
        append('"').append(name).append("\":")
        appendFiniteOrNull(value)
    }

    private fun StringBuilder.appendFiniteOrNull(value: Double?) {
        if (value != null && value.isFinite()) append(value) else append("null")
    }

    private fun StringBuilder.appendStringField(
        name: String,
        value: String,
    ) {
        append('"').append(name).append("\":")
        appendEscaped(value)
    }

    private fun StringBuilder.appendEscaped(value: String) {
        append('"')
        for (character in value) {
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\b' -> append("\\b")
                character == '\u000C' -> append("\\f")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' -> appendUnicodeEscape(character)
                else -> append(character)
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(character: Char) {
        val code = character.code
        val highNibble = code shr NIBBLE_BITS and NIBBLE_MASK
        append("\\u00")
        append(HEX_DIGITS[highNibble])
        append(HEX_DIGITS[code and NIBBLE_MASK])
    }

    private fun StringBuilder.appendWaypoint(waypoint: Waypoint) {
        append('{')
        appendLongField("timestampMillis", waypoint.timestampMillis)
        append(',')
        appendDoubleField("lat", waypoint.lat)
        append(',')
        appendDoubleField("lon", waypoint.lon)
        append(',')
        appendNullableDoubleField("elevationM", waypoint.elevationM)
        append('}')
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF
}
