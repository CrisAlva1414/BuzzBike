package com.buzzbike.app.ui.garage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.domain.wear.Bike
import com.buzzbike.app.domain.wear.BikeRepository
import com.buzzbike.app.domain.wear.ComponentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the garage: bike list, selected bike, wear and mutations.
 *
 * The wear assessments come from [ComponentRepository.assessWear], which keeps
 * [com.buzzbike.app.domain.wear.WearEngine] as the only wear formula. The view
 * model never recomputes ratios or thresholds; it only combines the Room flows
 * with the selected bike and a refresh tick that mutations bump.
 *
 * Every mutation is serialized by [GarageUiState.Content.isBusy]: while a write
 * is in flight a second tap is ignored, and the UI disables the controls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GarageViewModel
    @Inject
    constructor(
        private val bikeRepository: BikeRepository,
        private val componentRepository: ComponentRepository,
    ) : ViewModel() {
        private val selectedBikeId = MutableStateFlow<String?>(null)
        private val busy = MutableStateFlow(false)
        private val message = MutableStateFlow<String?>(null)
        private val refresh = MutableStateFlow(0L)

        private val _form = MutableStateFlow<GarageForm?>(null)
        val form: StateFlow<GarageForm?> = _form.asStateFlow()

        val state: StateFlow<GarageUiState> =
            combine(
                bikeRepository.observeBikes(),
                selectedBikeId,
                busy,
                message,
                refresh,
            ) { bikes, selected, isBusy, notice, _ ->
                Snapshot(bikes, selected, isBusy, notice)
            }.mapLatest { snapshot -> buildState(snapshot) }
                .catch { emit(GarageUiState.Error(LOAD_ERROR)) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                    initialValue = GarageUiState.Loading,
                )

        /** Selects [bikeId]; the content converges on the next flow emission. */
        fun selectBike(bikeId: String) {
            selectedBikeId.value = bikeId
        }

        /** Replaces the open form with an already-updated copy. */
        fun updateForm(form: GarageForm) {
            _form.value = form
        }

        /** Resolves [target] against the current content and opens its form. */
        fun openForm(target: GarageFormTarget) {
            _form.value =
                when (target) {
                    GarageFormTarget.CreateBike -> GarageForm.Bike(bikeId = null, name = "")
                    is GarageFormTarget.RenameBike -> renameForm(state.value, target.bikeId)
                    is GarageFormTarget.CreateComponent -> newComponentForm(target.bikeId)
                    is GarageFormTarget.ServiceComponent -> serviceForm(state.value, target.componentId)
                }
        }

        /** Closes the form without persisting anything. */
        fun closeForm() {
            _form.value = null
        }

        /** Validates and submits whichever form is currently open. */
        fun submitForm() {
            when (val current = _form.value) {
                null -> Unit
                is GarageForm.Bike -> {
                    val nameError = bikeNameError(current.name)
                    if (nameError != null) {
                        _form.value = current.copy(nameError = nameError)
                    } else {
                        performMutation { submitBikeForm(bikeRepository, current) }
                    }
                }
                is GarageForm.Component -> {
                    val validated = validateComponentForm(current)
                    val lifecycle = validated.lifecycle
                    if (validated.form.labelError != null || lifecycle == null) {
                        _form.value = validated.form
                    } else {
                        performMutation {
                            submitComponentForm(componentRepository, validated.form, lifecycle)
                        }
                    }
                }
                is GarageForm.Service -> {
                    val validated = validateServiceForm(current)
                    if (validated.form.kmError != null || validated.form.hoursError != null) {
                        _form.value = validated.form
                    } else {
                        performMutation { submitServiceForm(componentRepository, validated) }
                    }
                }
            }
        }

        /** Archives or restores a bike without touching its components or rides. */
        fun setBikeArchived(
            bikeId: String,
            archived: Boolean,
        ) {
            val bike =
                (state.value as? GarageUiState.Content)
                    ?.bikes
                    ?.firstOrNull { it.bike.id == bikeId }
                    ?.bike
                    ?: return
            performMutation {
                bikeRepository.updateBike(bike.copy(isArchived = archived))
                MutationOutcome(if (archived) BIKE_ARCHIVED_MESSAGE else BIKE_RESTORED_MESSAGE)
            }
        }

        /**
         * Deletes a bike; its rides stay in history keeping the historical
         * `bike_id`, so the detail screen reports that the bike no longer
         * exists.
         */
        fun deleteBike(bikeId: String) {
            performMutation {
                bikeRepository.deleteBike(bikeId)
                if (selectedBikeId.value == bikeId) {
                    selectedBikeId.value = null
                }
                MutationOutcome(BIKE_DELETED_MESSAGE)
            }
        }

        /** Soft-retires a component; it disappears from the active list. */
        fun retireComponent(componentId: String) {
            performMutation {
                componentRepository.retireComponent(componentId)
                MutationOutcome(COMPONENT_RETIRED_MESSAGE)
            }
        }

        /** Runs one serialized write and maps success/failure to UI state. */
        private fun performMutation(block: suspend () -> MutationOutcome) {
            if (busy.value) return
            busy.value = true
            message.value = null
            viewModelScope.launch {
                try {
                    val outcome = block()
                    message.value = outcome.message
                    outcome.selectedBikeId?.let { selectedBikeId.value = it }
                    _form.value = null
                    refresh.value += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    message.value = MUTATION_ERROR
                } finally {
                    busy.value = false
                }
            }
        }

        private suspend fun buildState(snapshot: Snapshot): GarageUiState {
            val selected =
                snapshot.selectedBikeId
                    ?.takeIf { id -> snapshot.bikes.any { it.id == id } }
                    ?: snapshot.bikes.firstOrNull { !it.isArchived }?.id
                    ?: snapshot.bikes.firstOrNull()?.id
            val summaries =
                snapshot.bikes.map { bike ->
                    val components = componentRepository.assessWear(bike.id)
                    BikeWearSummary(bike, components, worstAlertStatus(components))
                }
            return if (summaries.isEmpty()) {
                GarageUiState.Empty(snapshot.isBusy, snapshot.message)
            } else {
                GarageUiState.Content(summaries, selected, snapshot.isBusy, snapshot.message)
            }
        }

        private data class Snapshot(
            val bikes: List<Bike>,
            val selectedBikeId: String?,
            val isBusy: Boolean,
            val message: String?,
        )

        private companion object {
            const val LOAD_ERROR = "No se pudo cargar el garaje."
            const val MUTATION_ERROR = "No se pudo guardar el cambio; reintentá."
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        }
    }

private const val BIKE_ARCHIVED_MESSAGE = "Bici archivada. Sus rides siguen en el historial."
private const val BIKE_RESTORED_MESSAGE = "Bici restaurada."
private const val BIKE_DELETED_MESSAGE = "Bici borrada. Sus rides siguen en el historial."
private const val COMPONENT_RETIRED_MESSAGE = "Componente retirado."
