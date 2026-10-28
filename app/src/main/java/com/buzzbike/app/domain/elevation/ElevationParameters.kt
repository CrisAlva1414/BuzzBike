package com.buzzbike.app.domain.elevation

/**
 * Parameters for elevation processing per ADR-006.
 */
object ElevationParameters {
    // Hysteresis thresholds
    const val HYSTERESIS_BARO_M = 3.0
    const val HYSTERESIS_GPS_M = 10.0

    // Smoothing
    const val MEDIAN_WINDOW = 3
    const val SG_WINDOW_SECONDS = 9
    const val SG_ORDER = 2

    // Drift detection
    const val STOP_DRIFT_THRESHOLD_SECONDS = 30L
    const val STOP_SPEED_THRESHOLD_MPS = 0.5

    // Elevator guard
    const val ELEVATOR_DELTA_H_M = 3.0
    const val ELEVATOR_DELTA_S_M = 5.0
    const val ELEVATOR_ACCEL_QUIET_MPS2 = 1.5

    // Grade/VAM
    const val GRADE_WINDOW_MIN_M = 50.0
    const val GRADE_WINDOW_MAX_M = 100.0
    const val VAM_WINDOW_SECONDS = 10L

    // Anchor
    const val ANCHOR_AVG_SECONDS = 25L
    const val ANCHOR_MIN_VERTICAL_ACCURACY_M = 10.0
    const val REANCHOR_INTERVAL_SECONDS = 600L

    // Barometric formula (US Standard Atmosphere 1976)
    const val BARO_SEA_LEVEL_PRESSURE_HPA = 1013.25
    const val BARO_EXPONENT = 1.0 / 5.255

    // h = 44330 * (1 - (p/p0)^(1/5.255)); coefficient in metres.
    const val BARO_ALTITUDE_COEFFICIENT_M = 44330.0
}
