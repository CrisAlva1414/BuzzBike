package com.buzzbike.app.ui.garage

private const val DECIMAL_COMMA = ','
private const val DECIMAL_DOT = '.'

/** Parses a user-entered decimal, accepting a comma as the decimal separator. */
internal fun parseDecimal(text: String): Double? = text.trim().replace(DECIMAL_COMMA, DECIMAL_DOT).toDoubleOrNull()
