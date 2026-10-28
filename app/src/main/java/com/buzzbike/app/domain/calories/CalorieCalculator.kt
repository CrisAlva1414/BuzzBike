package com.buzzbike.app.domain.calories

import kotlin.math.max

/**
 * Calorie calculator implementing ADR-001.
 * Sample-by-sample MET integration, optional profile, uncertainty bands.
 */
class CalorieCalculator {
    
    fun calculate(
        speedSamplesMps: List<Double>,
        durationsSeconds: List<Double>,
        isMoving: List<Boolean>,
        profile: UserProfile = UserProfile(),
    ): CalorieResult {
        if (speedSamplesMps.isEmpty() || durationsSeconds.isEmpty()) {
            return CalorieResult(
                totalKcal = null,
                activeKcal = null,
                metMinutes = 0.0,
                model = CalorieModel.NONE,
                uncertaintyMinPercent = 30,
                uncertaintyMaxPercent = 40,
                hasProfile = false,
                missingInputs = listOf("no data"),
            )
        }
        
        val hasProfile = profile.weightKg != null && profile.weightKg > 0
        val weightKg = profile.weightKg ?: 70.0
        val assumedWeight = if (profile.weightKg == null) 70.0 else null
        
        val missingInputs = mutableListOf<String>()
        if (profile.weightKg == null) missingInputs.add("weight")
        if (profile.heightCm == null) missingInputs.add("height")
        if (profile.ageYears == null) missingInputs.add("age")
        if (profile.isMale == null) missingInputs.add("sex")
        
        var totalMetMinutes = 0.0
        var activeMetMinutes = 0.0
        
        val count = minOf(speedSamplesMps.size, durationsSeconds.size, isMoving.size)
        for (i in 0 until count) {
            val durationSec = durationsSeconds[i]
            if (durationSec <= 0 || !durationSec.isFinite()) continue
            
            val durationMin = durationSec / 60.0
            val moving = isMoving[i]
            
            if (!moving) {
                // Stops at 1.0 MET
                totalMetMinutes += MetTable.STOP_MET * durationMin
                continue
            }
            
            val speed = speedSamplesMps[i]
            val metBand = MetTable.getMetForSpeedMps(speed)
            if (metBand == null) {
                // Gap or unclassified - treat as stop
                totalMetMinutes += MetTable.STOP_MET * durationMin
                continue
            }
            
            val met = metBand.met
            totalMetMinutes += met * durationMin
            activeMetMinutes += met * durationMin
        }
        
        val totalKcal = if (hasProfile || assumedWeight != null) {
            (totalMetMinutes * 3.5 * weightKg / 200.0)
        } else {
            null
        }
        
        val activeKcal = if (hasProfile || assumedWeight != null) {
            (activeMetMinutes * 3.5 * weightKg / 200.0)
        } else {
            null
        }
        
        val (minPct, maxPct) = if (hasProfile) {
            Pair(20, 30)
        } else {
            Pair(30, 40)
        }
        
        return CalorieResult(
            totalKcal = totalKcal,
            activeKcal = activeKcal,
            metMinutes = totalMetMinutes,
            model = CalorieModel.COMPENDIUM_VELOCITY,
            uncertaintyMinPercent = minPct,
            uncertaintyMaxPercent = maxPct,
            hasProfile = hasProfile,
            missingInputs = missingInputs,
            assumedWeightKg = assumedWeight,
        )
    }
    
    private fun minOf(a: Int, b: Int, c: Int): Int = minOf(minOf(a, b), c)
}
