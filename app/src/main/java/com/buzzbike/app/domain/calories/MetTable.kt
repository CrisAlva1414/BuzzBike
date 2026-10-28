package com.buzzbike.app.domain.calories

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
    
    val bands = listOf(
        MetBand("01010", 0.0, 10.0, 4.0),
        MetBand("01020", 10.0, 11.9, 6.8),
        MetBand("01030", 12.0, 13.9, 8.0),
        MetBand("01040", 14.0, 15.9, 10.0),
        MetBand("01050", 16.0, 19.0, 12.0, isEstimated = true),
        MetBand("01060", 20.0, Double.POSITIVE_INFINITY, 16.8),
    )
    
    const val STOP_MET = 1.0 // Compendium 07021
    const val MPH_TO_KMH = 1.609344
    
    /**
     * Get MET for speed in m/s. Uses mph bands.
     * Convention: inclusive of lower bound, exclusive of upper bound except last.
     */
    fun getMetForSpeedMps(speedMps: Double): MetBand? {
        if (!speedMps.isFinite() || speedMps < 0.0) return null
        if (speedMps < 0.447) return null // < 1 mph, treat as stop
        
        val mph = speedMps * 2.23694
        return when {
            mph < 10.0 -> bands[0]
            mph < 11.9 -> bands[1]
            mph < 13.9 -> bands[2]
            mph < 15.9 -> bands[3]
            mph < 19.0 -> bands[4]
            mph >= 20.0 -> bands[5]
            else -> null // 19.0-19.99 mph gap - no band
        }
    }
}
