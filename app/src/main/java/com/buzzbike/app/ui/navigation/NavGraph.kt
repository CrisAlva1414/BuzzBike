package com.buzzbike.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buzzbike.app.ui.history.HistoryScreen
import com.buzzbike.app.ui.history.RideDetailScreen
import com.buzzbike.app.ui.ride.RideScreen
import com.buzzbike.app.ui.settings.SyncSettingsScreen

private const val RIDE_ROUTE = "ride"
private const val SETTINGS_ROUTE = "settings"
private const val HISTORY_ROUTE = "history"
private const val RIDE_DETAIL_ROUTE = "history/{rideId}"
private const val RIDE_ID_ARG = "rideId"

private fun rideDetailRoute(id: String): String = "history/${Uri.encode(id)}"

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
                onOpenHistory = {
                    navController.navigate(HISTORY_ROUTE) { launchSingleTop = true }
                },
            )
        }
        composable(SETTINGS_ROUTE) {
            SyncSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(HISTORY_ROUTE) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenRide = { id ->
                    navController.navigate(rideDetailRoute(id)) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = RIDE_DETAIL_ROUTE,
            arguments = listOf(navArgument(RIDE_ID_ARG) { type = NavType.StringType }),
        ) {
            RideDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
