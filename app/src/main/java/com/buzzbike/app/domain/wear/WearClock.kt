package com.buzzbike.app.domain.wear

/**
 * Injectable wall clock for the garage data layer.
 *
 * Wear accumulation needs "now" to compute elapsed days. Taking it as a port
 * keeps the repository deterministic in JVM tests instead of reading the
 * system clock directly.
 */
fun interface WearClock {
    fun nowMillis(): Long
}
