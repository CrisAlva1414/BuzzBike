package com.buzzbike.app.data.local.dao

/**
 * Aggregate returned by [RideDao.usageForBikeSince].
 *
 * `COALESCE` makes both values non-null when no ride matches, so a component
 * installed before its first ride reports zero usage instead of `null`.
 */
data class RideUsageSum(
    val distanceM: Double,
    val movingTimeSeconds: Long,
)
