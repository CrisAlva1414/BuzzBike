package com.buzzbike.app.domain.elevation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProcessorTest {
    
    private val nanosPerSecond = 1_000_000_000L
    
    @Test
    fun given_flatRide_withBarometer_thenGainIsZero() {
        val samples = (0..60).map { i ->
            ElevationSample(
                timestampNanos = i * nanosPerSecond,
                pressureHpa = 1013.25,
                altitudeGpsMslM = 100.0,
                verticalAccuracyM = 5.0,
                speedMps = 5.0,
            )
        }
        
        val processor = ElevationProcessor()
        val result = processor.process(samples)
        
        assertEquals(0.0, result.gainM, 0.1)
        assertEquals(0.0, result.lossM, 0.1)
        assertTrue(result.hasBarometer)
    }
    
    @Test
    fun given_climb_withBarometer_thenGainDetected() {
        val samples = mutableListOf<ElevationSample>()
        // Start at 100m
        samples.add(
            ElevationSample(
                timestampNanos = 0,
                pressureHpa = 1000.0,
                altitudeGpsMslM = 100.0,
                verticalAccuracyM = 5.0,
                speedMps = 5.0,
            )
        )
        // Climb 50m over 60 seconds
        for (i in 1..60) {
            val pressure = 1000.0 + (i * 0.5) // pressure increases as altitude decreases? Wait - lower pressure = higher altitude
            // Actually: higher altitude = lower pressure. So climbing means pressure drops.
            val p = 1000.0 - (i * 0.3)
            samples.add(
                ElevationSample(
                    timestampNanos = i * nanosPerSecond,
                    pressureHpa = p,
                    altitudeGpsMslM = 100.0 + i * 0.8,
                    verticalAccuracyM = 5.0,
                    speedMps = 5.0,
                )
            )
        }
        
        val processor = ElevationProcessor()
        val result = processor.process(samples)
        
        assertTrue(result.gainM > 10.0)
    }
    
    @Test
    fun given_elevator_movement_thenNotCounted() {
        val samples = mutableListOf<ElevationSample>()
        samples.add(
            ElevationSample(
                timestampNanos = 0,
                pressureHpa = 1000.0,
                altitudeGpsMslM = 100.0,
                verticalAccuracyM = 5.0,
                speedMps = 0.0,
                accelMagnitudeMps2 = 0.5,
            )
        )
        // Sudden vertical movement with no horizontal speed and low acceleration
        samples.add(
            ElevationSample(
                timestampNanos = 5 * nanosPerSecond,
                pressureHpa = 995.0,
                altitudeGpsMslM = 110.0,
                verticalAccuracyM = 5.0,
                speedMps = 0.1,
                accelMagnitudeMps2 = 0.3,
            )
        )
        
        val processor = ElevationProcessor()
        val result = processor.process(samples)
        
        // Elevator guard should prevent counting
        assertTrue(result.gainM < 5.0)
    }
    
    @Test
    fun given_gpsOnly_thenUsesLargerHysteresis() {
        val samples = (0..30).map { i ->
            ElevationSample(
                timestampNanos = i * nanosPerSecond,
                pressureHpa = null,
                altitudeGpsMslM = 100.0 + (if (i % 5 == 0) 2.0 else 0.0),
                verticalAccuracyM = 8.0,
                speedMps = 5.0,
            )
        }
        
        val processor = ElevationProcessor()
        val result = processor.process(samples)
        
        // With 10m hysteresis, 2m bumps shouldn't count
        assertEquals(0.0, result.gainM, 0.1)
        assertEquals(ElevationSource.GPS_MSL, result.source)
    }
    
    @Test
    fun given_noData_thenZero() {
        val processor = ElevationProcessor()
        val result = processor.process(emptyList())
        
        assertEquals(0.0, result.gainM, 0.001)
        assertEquals(0.0, result.lossM, 0.001)
        assertEquals(ElevationSource.NONE, result.source)
    }
}
