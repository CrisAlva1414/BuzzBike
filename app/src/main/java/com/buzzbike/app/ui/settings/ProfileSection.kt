package com.buzzbike.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val COMPLETE_COLOR = Color(0xFF2E7D32)
private const val PROFILE_PURPOSE_TEXT =
    "Se usa para estimar calorías con una banda de incertidumbre más angosta. " +
        "Se guarda solo en este dispositivo y podés borrarlo cuando quieras."
private const val CLEAR_CONFIRM_TEXT =
    "Se borrará tu perfil corporal de este dispositivo. Las rutas y sus " +
        "calorías históricas no se modifican."

/**
 * Body-profile section embedded in the settings screen.
 *
 * Inputs are metric (kg, cm, years) and show a friendly error when out of
 * range. The section deliberately avoids fixed heights and horizontal packed
 * controls so it keeps working with large font scales; every interactive
 * element has at least a 48 dp touch target.
 */
@Composable
fun ProfileSection(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Perfil corporal",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = PROFILE_PURPOSE_TEXT,
            style = MaterialTheme.typography.bodySmall,
        )

        ProfileNumberField(
            value = state.weight,
            onValueChange = viewModel::onWeightChange,
            label = "Peso (kg)",
            error = state.weightError,
            keyboardType = KeyboardType.Decimal,
        )
        ProfileNumberField(
            value = state.height,
            onValueChange = viewModel::onHeightChange,
            label = "Altura (cm)",
            error = state.heightError,
            keyboardType = KeyboardType.Decimal,
        )
        ProfileNumberField(
            value = state.age,
            onValueChange = viewModel::onAgeChange,
            label = "Edad (años)",
            error = state.ageError,
            keyboardType = KeyboardType.Number,
        )

        Text(
            text = "Sexo",
            style = MaterialTheme.typography.labelLarge,
        )
        SexSelector(
            selected = state.isMale,
            onSelect = viewModel::onSexChange,
        )

        Text(
            text = if (state.isComplete) "Perfil completo" else "Perfil incompleto",
            color =
                if (state.isComplete) {
                    COMPLETE_COLOR
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            style = MaterialTheme.typography.titleSmall,
        )
        if (!state.isComplete) {
            Text(
                text =
                    "Ingresá peso, altura, edad y sexo válidos para usar la " +
                        "banda de calorías más precisa.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.saved) {
            Text(
                text = "Perfil guardado.",
                color = COMPLETE_COLOR,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Guardar perfil")
        }
        OutlinedButton(
            onClick = { showClearConfirm = true },
            enabled = state.hasProfile && !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Borrar perfil")
        }
    }

    if (showClearConfirm) {
        ClearProfileDialog(
            onConfirm = {
                showClearConfirm = false
                viewModel.clear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** Numeric field with an inline, screen-reader-friendly error message. */
@Composable
private fun ProfileNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Two-option radio group; each row exposes a full-width 48 dp target. */
@Composable
private fun SexSelector(
    selected: Boolean?,
    onSelect: (Boolean) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        SexOption("Masculino", value = true, selected = selected, onSelect = onSelect)
        SexOption("Femenino", value = false, selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun SexOption(
    label: String,
    value: Boolean,
    selected: Boolean?,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    role = Role.RadioButton,
                ).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == value,
            onClick = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label)
    }
}

/** Confirmation before deleting the local profile. */
@Composable
private fun ClearProfileDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Borrar perfil") },
        text = { Text(CLEAR_CONFIRM_TEXT) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Borrar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
