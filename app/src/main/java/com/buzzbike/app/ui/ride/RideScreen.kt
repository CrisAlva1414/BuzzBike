package com.buzzbike.app.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.Locale

private const val METERS_PER_KILOMETER = 1000.0
private const val MPS_TO_KPH = 3.6

@Composable
fun RideScreen(viewModel: RideViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "BuzzBike",
            style = MaterialTheme.typography.headlineLarge,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Distance: ${String.format(
                        Locale.getDefault(),
                        "%.2f",
                        uiState.distanceM / METERS_PER_KILOMETER,
                    )} km",
                )
                Text("Moving Time: ${uiState.movingTimeSeconds}s")
                Text(
                    "Elevation Gain: ${String.format(Locale.getDefault(), "%.0f", uiState.elevationGainM)} m",
                )
                Text(
                    "Calories: ${
                        uiState.caloriesKcal?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--"
                    } kcal",
                )
                Text(
                    "Avg Speed: ${String.format(Locale.getDefault(), "%.1f", uiState.avgSpeedMps * MPS_TO_KPH)} km/h",
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (uiState.isTracking) {
                    viewModel.stopRide()
                } else {
                    viewModel.startRide()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.isTracking) "Stop Ride" else "Start Ride")
        }
    }
}
