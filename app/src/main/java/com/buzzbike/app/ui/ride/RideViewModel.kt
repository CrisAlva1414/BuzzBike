package com.buzzbike.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.data.repository.RideRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RideUiState(
    val isTracking: Boolean = false,
    val activeRideId: String? = null,
    val distanceM: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val elevationGainM: Double = 0.0,
    val caloriesKcal: Double? = null,
    val avgSpeedMps: Double = 0.0,
)

@HiltViewModel
class RideViewModel
    @Inject
    constructor(
        private val rideRepository: RideRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RideUiState())
        val uiState: StateFlow<RideUiState> = _uiState.asStateFlow()

        fun startRide() {
            viewModelScope.launch {
                val rideId = rideRepository.startRide()
                _uiState.value =
                    _uiState.value.copy(
                        isTracking = true,
                        activeRideId = rideId,
                    )
            }
        }

        fun stopRide() {
            viewModelScope.launch {
                val rideId = _uiState.value.activeRideId
                if (rideId != null) {
                    rideRepository.stopRide(rideId)
                    _uiState.value =
                        _uiState.value.copy(
                            isTracking = false,
                            activeRideId = null,
                        )
                }
            }
        }
    }
