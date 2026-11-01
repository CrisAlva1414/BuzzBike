package com.buzzbike.app.ui.garage

internal const val BIKE_NAME_REQUIRED_MESSAGE = "El nombre de la bici es obligatorio."
internal const val BIKE_NAME_TOO_LONG_MESSAGE = "El nombre no puede superar los 40 caracteres."

/** `null` when [name] is valid; a user-facing message otherwise. */
internal fun bikeNameError(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> BIKE_NAME_REQUIRED_MESSAGE
        trimmed.length > MAX_BIKE_NAME_LENGTH -> BIKE_NAME_TOO_LONG_MESSAGE
        else -> null
    }
}
