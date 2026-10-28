package com.buzzbike.app.domain.ride.engine

import com.buzzbike.app.domain.calories.CalorieCalculator
import com.buzzbike.app.domain.calories.CalorieResult
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.elevation.ElevationDescriptors
import com.buzzbike.app.domain.elevation.ElevationProcessor
import com.buzzbike.app.domain.elevation.ElevationSample
import com.buzzbike.app.domain.model.RideDescriptors
import com.buzzbike.app.domain.model.TrackPoint
import com.buzzbike.app.domain.ride.RideAggregator
import com.buzzbike.app.domain.ride.TraceCleaner
import javax.inject.Inject
import javax.inject.Singleton

private const val NANOS_PER_SECOND = 1e9
private const val MAX_SAMPLE_GAP_SECONDS = 60.0
private const val MOVING_SPEED_THRESHOLD_MPS = 0.4

/**
 * Orchestrates domain processing: cleaning, segmentation, elevation, calories.
 */
@Singleton
open class RideEngine
    @Inject
    constructor() {
        private val elevationProcessor = ElevationProcessor()
        private val calorieCalculator = CalorieCalculator()

        open fun processRide(
            rawPoints: List<TrackPoint>,
            elevationSamples: List<ElevationSample> = emptyList(),
            profile: UserProfile = UserProfile(),
        ): RideEngineResult {
            val cleaned = TraceCleaner.clean(rawPoints)
            val rideDescriptors = RideAggregator.describe(cleaned)
            val elevationDescriptors = elevationProcessor.process(elevationSamples)

            // Build calorie inputs from ride descriptors
            val speedSamples = cleaned.points.mapNotNull { it.speedMps }
            val durations = mutableListOf<Double>()
            val isMoving = mutableListOf<Boolean>()

            // Simple approximation - in real use, use moving time estimator
            for (i in 1 until cleaned.points.size) {
                val dt =
                    (cleaned.points[i].timestampMonotonicNanos - cleaned.points[i - 1].timestampMonotonicNanos) /
                        NANOS_PER_SECOND
                if (dt > 0 && dt < MAX_SAMPLE_GAP_SECONDS) {
                    durations.add(dt)
                    val speed = cleaned.points[i].speedMps
                    isMoving.add(speed != null && speed > MOVING_SPEED_THRESHOLD_MPS)
                }
            }

            val calorieResult =
                if (durations.isNotEmpty()) {
                    calorieCalculator.calculate(
                        speedSamplesMps = speedSamples.take(durations.size),
                        durationsSeconds = durations,
                        isMoving = isMoving,
                        profile = profile,
                    )
                } else {
                    null
                }

            return RideEngineResult(
                rideDescriptors = rideDescriptors,
                elevationDescriptors = elevationDescriptors,
                calorieResult = calorieResult,
                acceptedPoints = cleaned.points.size,
                rejectedPoints = cleaned.rejectedCount,
            )
        }
    }

data class RideEngineResult(
    val rideDescriptors: RideDescriptors,
    val elevationDescriptors: ElevationDescriptors,
    val calorieResult: CalorieResult?,
    val acceptedPoints: Int,
    val rejectedPoints: Int,
)
