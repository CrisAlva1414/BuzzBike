package com.buzzbike.app.domain.ride

import com.buzzbike.app.domain.model.TrackPoint

/**
 * Single source of truth for usable reported speed (ADR-005 §3).
 *
 * Returns [TrackPoint.speedMps] only when it is finite and non-negative;
 * otherwise returns `null`. No speed is inferred from distance/time between
 * consecutive fixes: ADR-005 §3 forbids it because urban GPS jitter corrupts
 * that estimate. A fix without usable speed is treated as "no evidence", never
 * as a zero-speed sample.
 */
object SpeedResolver {
    fun resolve(point: TrackPoint): Double? = point.speedMps?.takeIf { it.isFinite() && it >= 0.0 }
}
