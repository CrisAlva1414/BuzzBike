package com.buzzbike.app.ui.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.buzzbike.app.domain.wear.WearType

private const val MIN_TOUCH_DP = 48
private const val GAP_DP = 8
private const val RADIO_GAP_DP = 8
private const val BIKE_NAME_HINT = "Máximo 40 caracteres."
private const val COMPONENT_HINT =
    "Los ciclos son editables. Si no configurás ninguno, el componente no puede alertar."
private const val SERVICE_HINT =
    "Se guardan los valores consumidos antes del service y el acumulado se reinicia."
private const val NOTES_LABEL = "Notas (opcional)"

@Composable
fun BikeFormDialog(
    form: GarageForm.Bike,
    isBusy: Boolean,
    onChange: (GarageForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isCreate = form.bikeId == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreate) "Agregar bici" else "Renombrar bici") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                FormField(
                    value = form.name,
                    onValueChange = { onChange(form.copy(name = it, nameError = null)) },
                    label = "Nombre",
                    error = form.nameError,
                )
                if (form.nameError == null) {
                    Text(
                        text = BIKE_NAME_HINT,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isBusy) {
                Text(if (isCreate) "Crear" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
fun ComponentFormDialog(
    form: GarageForm.Component,
    isBusy: Boolean,
    onChange: (GarageForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar componente") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            ) {
                Text(
                    text = "Tipo",
                    style = MaterialTheme.typography.labelLarge,
                )
                Column(Modifier.selectableGroup()) {
                    WearType.entries.forEach { type ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = MIN_TOUCH_DP.dp)
                                    .selectable(
                                        selected = form.type == type,
                                        onClick = { onChange(componentFormWithType(form, type)) },
                                        role = Role.RadioButton,
                                    ).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = form.type == type,
                                onClick = null,
                            )
                            Spacer(Modifier.width(RADIO_GAP_DP.dp))
                            Text(text = GarageFormat.typeText(type))
                        }
                    }
                }
                FormField(
                    value = form.label,
                    onValueChange = { onChange(form.copy(label = it, labelError = null)) },
                    label = "Etiqueta",
                    error = form.labelError,
                )
                Text(
                    text = "Ciclo de vida (completá al menos uno)",
                    style = MaterialTheme.typography.labelLarge,
                )
                FormField(
                    value = form.lifecycleKm,
                    onValueChange = { onChange(form.copy(lifecycleKm = it, lifecycleError = null)) },
                    label = "Ciclo por km",
                    error = null,
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = form.lifecycleHours,
                    onValueChange = { onChange(form.copy(lifecycleHours = it, lifecycleError = null)) },
                    label = "Ciclo por horas",
                    error = null,
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = form.lifecycleDays,
                    onValueChange = { onChange(form.copy(lifecycleDays = it, lifecycleError = null)) },
                    label = "Ciclo por días",
                    error = form.lifecycleError,
                    keyboardType = KeyboardType.Decimal,
                )
                Text(
                    text = COMPONENT_HINT,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isBusy) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
fun ServiceFormDialog(
    form: GarageForm.Service,
    isBusy: Boolean,
    onChange: (GarageForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val notesError = serviceNotesError(form.notes) ?: form.notesError
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar servicio") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            ) {
                Text(
                    text = "Componente: ${form.componentLabel}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = SERVICE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                )
                FormField(
                    value = form.km,
                    onValueChange = { onChange(form.copy(km = it, kmError = null)) },
                    label = "Km consumidos",
                    error = form.kmError,
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = form.hours,
                    onValueChange = { onChange(form.copy(hours = it, hoursError = null)) },
                    label = "Horas consumidas",
                    error = form.hoursError,
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = form.notes,
                    onValueChange = { onChange(form.copy(notes = it, notesError = null)) },
                    label = NOTES_LABEL,
                    error = notesError,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isBusy && notesError == null) {
                Text("Registrar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
fun DeleteBikeDialog(
    bikeName: String,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Borrar bici") },
        text = {
            Text(
                text =
                    "Se borrará «$bikeName» y sus componentes. Los rides quedan en el " +
                        "historial; el detalle mostrará que la bici ya no existe. " +
                        "Esta acción no se puede deshacer.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isBusy) {
                Text("Borrar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
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
