package com.buzzbike.app.domain.ride

/**
 * Tuning parameters for trace cleanup, moving/stopped segmentation and ride
 * aggregation, as specified by ADR-005.
 *
 * Every value is either marked **source** (backed by a cited reference) or
 * **engineering decision** (a deliberate default that is versioned, not a
 * scientific constant). Changing any value that affects distance, moving time
 * or peak speed must bump [MODEL_VERSION] and update ADR-005.
 */
object RideParameters {
    /**
     * Speed below which a stop candidate starts.
     *
     * **Engineering decision** (ADR-005 §4 / parameter table): sits inside the
     * 0.3–1.9 m/s literature range and is conservative to avoid false stops.
     */
    const val V_STOP_MPS: Double = 0.4

    /**
     * Minimum time below [V_STOP_MPS] required to confirm a stop.
     *
     * **Engineering decision** (ADR-005 §4 / parameter table): below the
     * 15–120 s literature range to catch short traffic lights, compensated by
     * the displacement and accelerometer vetoes.
     */
    const val T_STOP_SECONDS: Long = 10

    /**
     * Speed above which a resume candidate starts.
     *
     * **Engineering decision** (ADR-005 §4): 2x [V_STOP_MPS] hysteresis, so the
     * 0.4–0.8 m/s dead band does not oscillate.
     */
    const val V_GO_MPS: Double = 0.8

    /**
     * Minimum time above [V_GO_MPS] required to confirm resumed movement.
     *
     * **Engineering decision** (ADR-005 §4): fast resume timer that avoids
     * underestimating moving time.
     */
    const val T_GO_SECONDS: Long = 3

    /**
     * Net displacement above which a stop candidate is vetoed.
     *
     * **Engineering decision** (ADR-005 §4): 8 m over
     * [STOP_DISPLACEMENT_WINDOW_SECONDS] equals 0.4 m/s, protecting slow
     * climbs. Only evaluated with `accuracyH <= LOW_QUALITY_ACCURACY_H_M`.
     */
    const val STOP_DISPLACEMENT_VETO_M: Double = 8.0

    /**
     * Window for [STOP_DISPLACEMENT_VETO_M].
     *
     * **Engineering decision** (ADR-005 §4).
     */
    const val STOP_DISPLACEMENT_WINDOW_SECONDS: Long = 20

    /**
     * Net displacement above which movement resumes even without [T_GO_SECONDS]
     * above [V_GO_MPS].
     *
     * **Engineering decision** (ADR-005 §4): 10 m over
     * [RESUME_OVERRIDE_WINDOW_SECONDS] equals 1 m/s, recovering Doppler lag
     * after a stop. Only evaluated with `accuracyH <= LOW_QUALITY_ACCURACY_H_M`.
     */
    const val RESUME_DISPLACEMENT_OVERRIDE_M: Double = 10.0

    /**
     * Window for [RESUME_DISPLACEMENT_OVERRIDE_M].
     *
     * **Engineering decision** (ADR-005 §4).
     */
    const val RESUME_OVERRIDE_WINDOW_SECONDS: Long = 10

    /**
     * Standard deviation of accel magnitude above which a stop candidate is
     * vetoed.
     *
     * **Engineering decision** (ADR-005 §4): above a still phone's noise floor
     * and below typical pedaling vibration; device-dependent and pending real
     * device calibration (ADR-005 TODO).
     */
    const val ACCEL_STILLNESS_SIGMA_MPS2: Double = 0.15

    /**
     * Window for [ACCEL_STILLNESS_SIGMA_MPS2].
     *
     * **Engineering decision** (ADR-005 §4).
     */
    const val ACCEL_STILLNESS_WINDOW_SECONDS: Long = 3

    /**
     * Horizontal accuracy above which a point is rejected.
     *
     * **Engineering decision** (ADR-005 §2 / parameter table): discards severe
     * urban-canyon fixes. Note that `Location.getAccuracy()` is a 68th
     * percentile radius, not a 95% bound.
     */
    const val MAX_ACCURACY_H_M: Double = 30.0

    /**
     * Horizontal accuracy at or below which a point is considered normal
     * quality; between this and [MAX_ACCURACY_H_M] the point is kept but marked
     * low quality.
     *
     * **Engineering decision** (ADR-005 §2).
     */
    const val LOW_QUALITY_ACCURACY_H_M: Double = 15.0

    /**
     * Implied speed above which a single-step jump is rejected as a teleport.
     *
     * **Engineering decision** (ADR-005 §2 / parameter table): 25 m/s
     * (90 km/h) is more likely multipath than cycling in one step.
     */
    const val TELEPORT_SPEED_MPS: Double = 25.0

    /**
     * Largest gap that is linearly interpolated (and whose distance is
     * accumulated).
     *
     * **Engineering decision** (ADR-005 §2): 5 s is small enough that linear
     * interpolation in lat/lon is acceptable.
     */
    const val GAP_INTERPOLATE_MAX_SECONDS: Long = 5

    /**
     * Gap above which the trace segment is cut.
     *
     * **Engineering decision** (ADR-005 §2): more than one minute without a
     * position is a sampling discontinuity, exported as separate GPX `trkseg`.
     */
    const val GAP_CUT_SECONDS: Long = 60

    /**
     * Version of the descriptor model and parameter set.
     *
     * **Engineering decision** (ADR-005 §7): any parameter or algorithm change
     * affecting distance, moving time or peak speed must bump this value and
     * update ADR-005.
     */
    const val MODEL_VERSION: String = "ride-descriptors-v1"
}
