package com.buzzbike.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buzzbike.app.di.IoDispatcher
import com.buzzbike.app.domain.calories.UserProfile
import com.buzzbike.app.domain.calories.UserProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Immutable UI state for the body-profile form.
 *
 * The numeric inputs are kept as raw text so the user can type freely; parsed
 * values only leave the view model when they are valid. Personal values are
 * never logged and never placed in content descriptions (AGENTS.md §7).
 */
data class ProfileUiState(
    val weight: String = "",
    val height: String = "",
    val age: String = "",
    val isMale: Boolean? = null,
    val weightError: String? = null,
    val heightError: String? = null,
    val ageError: String? = null,
    /** `true` when all four inputs are present and valid (ADR-001 §5.1). */
    val isComplete: Boolean = false,
    /** `true` when the form differs from the last loaded/saved profile. */
    val hasChanges: Boolean = false,
    /** Enables the save button: valid form, with something new to persist. */
    val canSave: Boolean = false,
    /** A save just succeeded; cleared on the next edit. */
    val saved: Boolean = false,
    /** A clear is in flight. */
    val clearing: Boolean = false,
    /** Any save/clear is in flight; used to disable mutating actions. */
    val isBusy: Boolean = false,
    /** A profile row exists, so "Borrar perfil" is meaningful. */
    val hasProfile: Boolean = false,
    /** Static, non-personal error about the persistence operation. */
    val error: String? = null,
)

/**
 * Settings-screen adapter over the persisted [UserProfileStore].
 *
 * The stored profile is optional and local-only (AGENTS.md §7). Partial
 * profiles are allowed because the calorie cascade falls back to a wider band;
 * the "Perfil completo" indicator only turns on when weight, height, age and
 * sex are all present and valid.
 *
 * Every write is guarded by [ProfileUiState.canSave]/[ProfileUiState.isBusy]
 * and by a re-check of the captured form generation, so a slow load can never
 * overwrite a later edit and a second tap cannot start an interleaved write.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val store: UserProfileStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileUiState())
        val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

        /** Last persisted value used to detect unsaved changes. */
        private var savedProfile: UserProfile? = null

        /** Bumped by every user edit, save or clear. */
        private val generation = AtomicInteger(0)

        /** Serializes save/clear so a clear always wins over an in-flight save. */
        private val mutationMutex = Mutex()

        /** Number of save/clear operations currently in flight. */
        private val mutationsInFlight = AtomicInteger(0)

        /** Makes [load] idempotent after the first successful read. */
        @Volatile
        private var loaded = false

        init {
            load()
        }

        /** Loads the stored profile once; a failed read is retryable. */
        fun load() {
            if (loaded) return
            val loadGeneration = generation.get()
            viewModelScope.launch {
                try {
                    val profile = withContext(ioDispatcher) { store.get() }
                    if (generation.get() == loadGeneration) {
                        loaded = true
                        savedProfile = profile
                        _uiState.value = profile.toFormState()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    _uiState.update { it.copy(error = LOAD_ERROR) }
                }
            }
        }

        fun onWeightChange(value: String) = edit { it.copy(weight = value) }

        fun onHeightChange(value: String) = edit { it.copy(height = value) }

        fun onAgeChange(value: String) = edit { it.copy(age = value) }

        fun onSexChange(isMale: Boolean) = edit { it.copy(isMale = isMale) }

        /**
         * Persists the form when it is valid and has changes.
         *
         * Validation always runs first: an out-of-range value never reaches the
         * repository. The completion is applied only when the form was not
         * edited while the write was in flight.
         */
        fun save() {
            val snapshot = _uiState.value
            if (!snapshot.canSave || snapshot.isBusy) return
            val profile = snapshot.toProfile() ?: return
            generation.incrementAndGet()
            val saveGeneration = generation.get()
            mutationsInFlight.incrementAndGet()
            _uiState.update {
                it.copy(saved = false, error = null, isBusy = true).withDerived(savedProfile)
            }
            viewModelScope.launch {
                try {
                    val persisted =
                        withContext(ioDispatcher) {
                            mutationMutex.withLock {
                                // A later clear/edit bumped the generation while
                                // this save waited for the lock: do not resurrect
                                // the row the user just deleted.
                                if (generation.get() != saveGeneration) {
                                    false
                                } else {
                                    store.save(profile)
                                    true
                                }
                            }
                        }
                    if (persisted && generation.get() == saveGeneration) {
                        savedProfile = profile
                        _uiState.update { it.copy(saved = true) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    if (generation.get() == saveGeneration) {
                        _uiState.update { it.copy(saved = false, error = SAVE_ERROR) }
                    }
                } finally {
                    mutationsInFlight.decrementAndGet()
                    val busy = mutationsInFlight.get() > 0
                    _uiState.update { it.copy(isBusy = busy).withDerived(savedProfile) }
                }
            }
        }

        /** Deletes the profile; historical rides keep their own snapshot. */
        fun clear() {
            generation.incrementAndGet()
            val clearGeneration = generation.get()
            mutationsInFlight.incrementAndGet()
            _uiState.update {
                it.copy(saved = false, clearing = true, isBusy = true, error = null)
            }
            viewModelScope.launch {
                try {
                    withContext(ioDispatcher) {
                        mutationMutex.withLock { store.clear() }
                    }
                    savedProfile = null
                    if (generation.get() == clearGeneration) {
                        _uiState.value = ProfileUiState()
                    } else {
                        _uiState.update { it.copy(saved = false) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    if (generation.get() == clearGeneration) {
                        _uiState.update { it.copy(error = CLEAR_ERROR) }
                    }
                } finally {
                    mutationsInFlight.decrementAndGet()
                    val busy = mutationsInFlight.get() > 0
                    _uiState.update {
                        it.copy(clearing = false, isBusy = busy).withDerived(savedProfile)
                    }
                }
            }
        }

        /** Applies an edit and refreshes errors, completeness and dirty state. */
        private fun edit(transform: (ProfileUiState) -> ProfileUiState) {
            generation.incrementAndGet()
            _uiState.update {
                transform(it).copy(saved = false, error = null).withDerived(savedProfile)
            }
        }
    }

/** Recomputes per-field errors and the derived flags from the current inputs. */
private fun ProfileUiState.withDerived(savedProfile: UserProfile?): ProfileUiState {
    val weightError = weightError(weight)
    val heightError = heightError(height)
    val ageError = ageError(age)
    val current = toProfile()
    val baseline = savedProfile ?: UserProfile()
    val hasErrors = weightError != null || heightError != null || ageError != null
    val hasChanges = current != baseline
    return copy(
        weightError = weightError,
        heightError = heightError,
        ageError = ageError,
        isComplete = current?.isComplete == true,
        hasChanges = hasChanges,
        canSave = !hasErrors && !isBusy && hasChanges,
        hasProfile = savedProfile != null,
    )
}

/** Parses the form into a profile; returns `null` when any field is invalid. */
private fun ProfileUiState.toProfile(): UserProfile? {
    if (weightError != null || heightError != null || ageError != null) return null
    return UserProfile(
        weightKg = parseDecimal(weight),
        heightCm = parseDecimal(height),
        ageYears = age.trim().takeIf(String::isNotEmpty)?.toIntOrNull(),
        isMale = isMale,
    )
}

/** Maps a persisted profile into its text form. */
private fun UserProfile?.toFormState(): ProfileUiState =
    this?.let { profile ->
        ProfileUiState(
            weight = profile.weightKg?.toString().orEmpty(),
            height = profile.heightCm?.toString().orEmpty(),
            age = profile.ageYears?.toString().orEmpty(),
            isMale = profile.isMale,
        ).withDerived(profile)
    } ?: ProfileUiState().withDerived(null)

private const val LOAD_ERROR = "No se pudo cargar el perfil; reintentá."
private const val SAVE_ERROR = "No se pudo guardar el perfil; reintentá."
private const val CLEAR_ERROR = "No se pudo borrar el perfil; reintentá."
