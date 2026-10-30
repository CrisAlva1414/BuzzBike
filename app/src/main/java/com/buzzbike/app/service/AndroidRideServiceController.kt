package com.buzzbike.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.buzzbike.app.domain.ride.session.RideServiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter for [RideServiceController].
 *
 * Keeps the [TrackingService] lifecycle out of the ViewModel so the UI layer
 * only depends on the domain port and can be tested on the JVM.
 */
@Singleton
class AndroidRideServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RideServiceController {
        override fun start() {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            )
        }

        override fun stop() {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
