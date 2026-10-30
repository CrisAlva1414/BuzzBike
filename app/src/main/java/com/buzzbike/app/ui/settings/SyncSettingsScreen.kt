package com.buzzbike.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MAX_RETRY_DIGITS = 3
private val SAVED_COLOR = Color(0xFF2E7D32)
private const val SYNC_CONSENT_TEXT =
    "Al activar el sync, cada ride enviará métricas agregadas y waypoints " +
        "decimados (cada 10 s, máximo 2000) al endpoint configurado. El track " +
        "GPS crudo no se envía. Podés desconectar en cualquier momento."
private const val DISCONNECT_CONFIRM_TEXT =
    "Se cancelará el sync, se borrarán los envíos pendientes y el secreto. " +
        "Los rides locales no se borran."

/**
 * Minimal sync configuration form.
 *
 * The HMAC secret lives only in a local `remember` and is never exposed through
 * [SyncSettingsUiState]; it is written once to sealed storage by the view model
 * and never logged (AGENTS.md §7).
 *
 * The whole screen is protected with `FLAG_SECURE` while composed so the
 * secret cannot appear in screenshots or the recent-apps thumbnail.
 *
 * Autofill is **not** explicitly disabled here: Compose does not expose a
 * supported opt-out for the platform autofill service on a text field, so this
 * screen relies on the password keyboard type plus the omission of any
 * `contentType`/autofill semantics. That limitation is intentional and must be
 * revisited if a stable opt-out becomes available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.uiState.collectAsState()

    // Hide the whole screen (secret included) from screenshots and recents.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    var secret by remember { mutableStateOf("") }
    var retryText by remember { mutableStateOf("") }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // Keep the numeric field in sync with loaded/persisted values.
    LaunchedEffect(state.retryCount) { retryText = state.retryCount.toString() }
    // The secret is write-only: clear it on the save event itself, not on the
    // sticky `isSaved` flag (which is already true after loading a config).
    LaunchedEffect(state.savedTick) {
        if (state.savedTick > 0) secret = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Atrás") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::onEndpointChange,
                label = { Text("Endpoint (https)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.healthCheckUrl,
                onValueChange = viewModel::onHealthCheckUrlChange,
                label = { Text("Health check URL (https)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = retryText,
                onValueChange = { input ->
                    if (input.length <= MAX_RETRY_DIGITS && input.all(Char::isDigit)) {
                        retryText = input
                        viewModel.onRetryCountChange(input)
                    }
                },
                label = { Text("Reintentos") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Secreto HMAC") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { password() },
            )

            Text(
                text =
                    "El secreto se genera en el NAS (32+ bytes) y se guarda " +
                        "cifrado en el dispositivo. La URL no debe incluir " +
                        "credenciales, query ni fragment.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = SYNC_CONSENT_TEXT,
                style = MaterialTheme.typography.bodySmall,
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isSaved) {
                Text(
                    text = "Configuración guardada.",
                    color = SAVED_COLOR,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = state.connectionStatus != ConnectionStatus.CHECKING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.connectionStatus == ConnectionStatus.CHECKING) {
                        "Probando conexión…"
                    } else {
                        "Probar conexión"
                    },
                )
            }
            when (state.connectionStatus) {
                ConnectionStatus.IDLE, ConnectionStatus.CHECKING -> Unit
                ConnectionStatus.OK ->
                    Text(
                        text = "Conexión verificada.",
                        color = SAVED_COLOR,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                ConnectionStatus.UNREACHABLE ->
                    Text(
                        text = "No se pudo alcanzar el backend.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                ConnectionStatus.MISCONFIGURED ->
                    Text(
                        text = "Configuración incompleta o inválida.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.save(secret) }) { Text("Guardar") }
                OutlinedButton(onClick = { showDisconnectConfirm = true }) {
                    Text("Desconectar")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Desconectar sync") },
            text = { Text(DISCONNECT_CONFIRM_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirm = false
                        viewModel.disconnect()
                        secret = ""
                    },
                ) {
                    Text("Desconectar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}

/** Unwraps [ContextWrapper]s to reach the hosting [Activity], if any. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
