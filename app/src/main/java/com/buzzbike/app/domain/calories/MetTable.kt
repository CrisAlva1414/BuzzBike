package com.buzzbike.app.domain.calories

/** 1 mph = 0.44704 m/s; below this the sample is treated as stopped. */
private const val MIN_USABLE_SPEED_MPS = 0.447

/** 1 m/s = 2.23694 mph. */
private const val MPS_TO_MPH = 2.23694

private const val BAND_1_MAX_MPH = 10.0
private const val BAND_2_MAX_MPH = 11.9
private const val BAND_3_MAX_MPH = 13.9
private const val BAND_4_MAX_MPH = 15.9
private const val BAND_5_MAX_MPH = 19.0
private const val TOP_BAND_MIN_MPH = 20.0

/**
 * Compendium of Physical Activities 2024 - Bicycling METs
 * Herrmann et al. 2024, DOI: 10.1016/j.jshs.2023.10.010
 * 01050 is ESTIMATED, not measured.
 */
object MetTable {
    data class MetBand(
        val code: String,
        val minMph: Double,
        val maxMph: Double,
        val met: Double,
        val isEstimated: Boolean = false,
    )

    private val band01010 = MetBand("01010", 0.0, 10.0, 4.0)
    private val band01020 = MetBand("01020", 10.0, 11.9, 6.8)
    private val band01030 = MetBand("01030", 12.0, 13.9, 8.0)
    private val band01040 = MetBand("01040", 14.0, 15.9, 10.0)
    private val band01050 = MetBand("01050", 16.0, 19.0, 12.0, isEstimated = true)
    private val band01060 = MetBand("01060", 20.0, Double.POSITIVE_INFINITY, 16.8)

    val bands = listOf(band01010, band01020, band01030, band01040, band01050, band01060)

    const val STOP_MET = 1.0 // Compendium 07021
    const val MPH_TO_KMH = 1.609344

    /**
     * Get MET for speed in m/s. Uses mph bands.
     * Convention: inclusive of lower bound, exclusive of upper bound except last.
     */
    fun getMetForSpeedMps(speedMps: Double): MetBand? {
        if (!speedMps.isFinite() || speedMps < MIN_USABLE_SPEED_MPS) return null

        val mph = speedMps * MPS_TO_MPH
        return when {
            mph < BAND_1_MAX_MPH -> band01010
            mph < BAND_2_MAX_MPH -> band01020
            mph < BAND_3_MAX_MPH -> band01030
            mph < BAND_4_MAX_MPH -> band01040
            mph < BAND_5_MAX_MPH -> band01050
            mph >= TOP_BAND_MIN_MPH -> band01060
            else -> null // 19.0-19.99 mph gap - no band
        }
    }
}
