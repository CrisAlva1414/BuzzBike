package com.buzzbike.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.buzzbike.app.ui.ride.RideScreen

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "ride",
    ) {
        composable("ride") {
            RideScreen()
        }
    }
}
