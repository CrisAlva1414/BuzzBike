package com.buzzbike.app.domain.elevation

import kotlin.math.pow

/**
 * Barometric altitude conversion using US Standard Atmosphere 1976.
 * h = 44330 * (1 - (p/p0)^(1/5.255))
 * p0 is not assumed to be 1013.25 hPa - use anchor for reference.
 */
object BarometricConverter {
    /**
     * Convert pressure to altitude relative to reference pressure.
     * @param pressureHpa Measured pressure
     * @param referencePressureHpa Reference pressure at known altitude (p0)
     * @return Altitude difference in meters (positive = higher altitude = lower pressure)
     */
    fun pressureToAltitudeDelta(
        pressureHpa: Double,
        referencePressureHpa: Double,
    ): Double {
        val usable = isUsablePressure(pressureHpa) && isUsablePressure(referencePressureHpa)
        val ratio = if (usable) pressureHpa / referencePressureHpa else 0.0
        if (!usable || ratio <= 0.0 || ratio > 1.0) return 0.0

        val altitude =
            ElevationParameters.BARO_ALTITUDE_COEFFICIENT_M *
                (1.0 - ratio.pow(ElevationParameters.BARO_EXPONENT))
        return if (altitude.isFinite()) altitude else 0.0
    }

    /**
     * Estimate reference pressure at sea level from known altitude and pressure.
     */
    fun estimateSeaLevelPressure(
        pressureHpa: Double,
        altitudeM: Double,
    ): Double {
        val fallback = ElevationParameters.BARO_SEA_LEVEL_PRESSURE_HPA
        val usable =
            isUsablePressure(pressureHpa) &&
                altitudeM.isFinite() &&
                altitudeM < ElevationParameters.BARO_ALTITUDE_COEFFICIENT_M
        if (!usable) return fallback

        val ratio = 1.0 - altitudeM / ElevationParameters.BARO_ALTITUDE_COEFFICIENT_M
        val p0 = pressureHpa / ratio.pow(ElevationParameters.BARO_EXPONENT)
        return if (p0.isFinite() && p0 > 0.0) p0 else fallback
    }

    private fun isUsablePressure(pressureHpa: Double): Boolean = pressureHpa > 0.0 && pressureHpa.isFinite()
}
