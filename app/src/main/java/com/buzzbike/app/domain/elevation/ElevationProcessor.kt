package com.buzzbike.app.domain.elevation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Elevation processor implementing ADR-006.
 * Barometer relative + GPS/DEM anchor, hysteresis, drift detection, elevator guard.
 */
class ElevationProcessor {
    
    private var anchorPressureHpa: Double? = null
    private var anchorAltitudeM: Double? = null
    private var anchorSource: ElevationSource = ElevationSource.NONE
    private var lastAcceptedAltitude: Double? = null
    private var lastAcceptedTimeNanos: Long = 0L
    private var gainM: Double = 0.0
    private var lossM: Double = 0.0
    private var hasBarometer: Boolean = false
    
    // For drift detection
    private var stopStartNanos: Long? = null
    private var stopStartPressure: Double? = null
    private var stopStartAltitude: Double? = null
    
    fun process(samples: List<ElevationSample>): ElevationDescriptors {
        if (samples.isEmpty()) {
            return ElevationDescriptors(
                gainM = 0.0,
                lossM = 0.0,
                netM = 0.0,
                source = ElevationSource.NONE,
                confidence = 0.0,
                hasBarometer = false,
                anchorSource = ElevationSource.NONE,
            )
        }
        
        hasBarometer = samples.any { it.pressureHpa != null && it.pressureHpa.isFinite() }
        
        // Establish anchor
        establishAnchor(samples)
        
        // Process samples
        val processed = mutableListOf<ProcessedPoint>()
        for (sample in samples) {
            val altitude = computeAltitude(sample) ?: continue
            processed.add(ProcessedPoint(sample.timestampNanos, altitude, sample))
        }
        
        if (processed.isEmpty()) {
            return ElevationDescriptors(
                gainM = 0.0,
                lossM = 0.0,
                netM = 0.0,
                source = if (hasBarometer) ElevationSource.BAROMETER else ElevationSource.NONE,
                confidence = 0.0,
                hasBarometer = hasBarometer,
                anchorSource = anchorSource,
            )
        }
        
        // Apply hysteresis
        applyHysteresis(processed)
        
        val source = if (hasBarometer && anchorSource != ElevationSource.NONE) {
            ElevationSource.BAROMETER
        } else if (anchorSource == ElevationSource.GPS_MSL) {
            ElevationSource.GPS_MSL
        } else if (anchorSource == ElevationSource.GPS_ELLIPSOID) {
            ElevationSource.GPS_ELLIPSOID
        } else {
            ElevationSource.NONE
        }
        
        val confidence = computeConfidence()
        val uncertainty = if (hasBarometer) 5.0 else 15.0
        
        return ElevationDescriptors(
            gainM = gainM,
            lossM = lossM,
            netM = gainM - lossM,
            source = source,
            confidence = confidence,
            hasBarometer = hasBarometer,
            anchorSource = anchorSource,
            uncertaintyM = uncertainty,
        )
    }
    
    private fun establishAnchor(samples: List<ElevationSample>) {
        if (anchorSource != ElevationSource.NONE) return
        
        // Try GPS MSL first
        val mslSamples = samples.filter { 
            it.altitudeGpsMslM != null && it.altitudeGpsMslM.isFinite() &&
            (it.verticalAccuracyM == null || it.verticalAccuracyM <= ElevationParameters.ANCHOR_MIN_VERTICAL_ACCURACY_M)
        }
        if (mslSamples.isNotEmpty()) {
            val avg = mslSamples.take(ElevationParameters.ANCHOR_AVG_SECONDS.toInt()).map { it.altitudeGpsMslM!! }.average()
            anchorAltitudeM = avg
            anchorSource = ElevationSource.GPS_MSL
            // Set baro anchor if available
            val baroSample = samples.firstOrNull { it.pressureHpa != null && it.pressureHpa.isFinite() }
            if (baroSample != null) {
                anchorPressureHpa = baroSample.pressureHpa
            }
            return
        }
        
        // Try GPS ellipsoid (less preferred)
        val ellipSamples = samples.filter { 
            it.altitudeGpsEllipsoidM != null && it.altitudeGpsEllipsoidM.isFinite()
        }
        if (ellipSamples.isNotEmpty()) {
            val avg = ellipSamples.take(ElevationParameters.ANCHOR_AVG_SECONDS.toInt()).map { it.altitudeGpsEllipsoidM!! }.average()
            anchorAltitudeM = avg
            anchorSource = ElevationSource.GPS_ELLIPSOID
            val baroSample = samples.firstOrNull { it.pressureHpa != null && it.pressureHpa.isFinite() }
            if (baroSample != null) {
                anchorPressureHpa = baroSample.pressureHpa
            }
            return
        }
        
        // Barometer only - need external anchor, mark as low confidence
        val baroSample = samples.firstOrNull { it.pressureHpa != null && it.pressureHpa.isFinite() }
        if (baroSample != null) {
            anchorPressureHpa = baroSample.pressureHpa
            anchorAltitudeM = 0.0 // relative only
            anchorSource = ElevationSource.BAROMETER
        }
    }
    
    private fun computeAltitude(sample: ElevationSample): Double? {
        // Prefer barometer if available and anchored
        if (hasBarometer && anchorPressureHpa != null && sample.pressureHpa != null && sample.pressureHpa.isFinite()) {
            val delta = BarometricConverter.pressureToAltitudeDelta(sample.pressureHpa, anchorPressureHpa!!)
            val base = anchorAltitudeM ?: 0.0
            return base + delta
        }
        
        // Fallback to GPS
        if (sample.altitudeGpsMslM != null && sample.altitudeGpsMslM.isFinite()) {
            return sample.altitudeGpsMslM
        }
        if (sample.altitudeGpsEllipsoidM != null && sample.altitudeGpsEllipsoidM.isFinite()) {
            return sample.altitudeGpsEllipsoidM
        }
        
        return null
    }
    
    private fun applyHysteresis(processed: List<ProcessedPoint>) {
        gainM = 0.0
        lossM = 0.0
        lastAcceptedAltitude = null
        lastAcceptedTimeNanos = 0L
        
        val hysteresis = if (hasBarometer) ElevationParameters.HYSTERESIS_BARO_M else ElevationParameters.HYSTERESIS_GPS_M
        
        for (point in processed) {
            // Check elevator guard
            if (isElevator(point, processed)) {
                continue
            }
            
            if (lastAcceptedAltitude == null) {
                lastAcceptedAltitude = point.altitude
                lastAcceptedTimeNanos = point.timestampNanos
                continue
            }
            
            val delta = point.altitude - lastAcceptedAltitude!!
            if (abs(delta) >= hysteresis) {
                if (delta > 0) {
                    gainM += delta
                } else {
                    lossM += abs(delta)
                }
                lastAcceptedAltitude = point.altitude
                lastAcceptedTimeNanos = point.timestampNanos
            }
        }
    }
    
    private fun isElevator(point: ProcessedPoint, allPoints: List<ProcessedPoint>): Boolean {
        val sample = point.sample
        if (sample.speedMps == null || sample.accelMagnitudeMps2 == null) return false
        if (!sample.speedMps.isFinite() || !sample.accelMagnitudeMps2.isFinite()) return false
        
        // If moving slowly/horizontally but large vertical change with low acceleration
        if (sample.speedMps < ElevationParameters.ELEVATOR_DELTA_S_M && 
            abs(point.altitude - (lastAcceptedAltitude ?: point.altitude)) > ElevationParameters.ELEVATOR_DELTA_H_M &&
            sample.accelMagnitudeMps2 < ElevationParameters.ELEVATOR_ACCEL_QUIET_MPS2) {
            return true
        }
        return false
    }
    
    private fun computeConfidence(): Double {
        var conf = 0.5
        if (hasBarometer && anchorSource != ElevationSource.NONE) conf += 0.3
        if (anchorSource == ElevationSource.GPS_MSL) conf += 0.1
        if (anchorSource == ElevationSource.BAROMETER) conf -= 0.2
        return max(0.0, min(1.0, conf))
    }
    
    private data class ProcessedPoint(
        val timestampNanos: Long,
        val altitude: Double,
        val sample: ElevationSample,
    )
}
