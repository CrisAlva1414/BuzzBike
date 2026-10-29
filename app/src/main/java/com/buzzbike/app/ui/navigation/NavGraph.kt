package com.buzzbike.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.buzzbike.app.ui.ride.RideScreen
import com.buzzbike.app.ui.settings.SyncSettingsScreen

private const val RIDE_ROUTE = "ride"
private const val SETTINGS_ROUTE = "settings"

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = RIDE_ROUTE,
    ) {
        composable(RIDE_ROUTE) {
            RideScreen(
                onOpenSettings = {
                    navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(SETTINGS_ROUTE) {
            SyncSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
