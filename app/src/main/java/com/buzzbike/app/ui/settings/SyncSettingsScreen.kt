package com.buzzbike.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MAX_RETRY_DIGITS = 3
private val SAVED_COLOR = Color(0xFF2E7D32)

/**
 * Minimal sync configuration form.
 *
 * The HMAC secret lives only in a local `remember` and is never exposed through
 * [SyncSettingsUiState]; it is written once to sealed storage by the view model
 * and never logged (AGENTS.md §7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.uiState.collectAsState()

    var secret by remember { mutableStateOf("") }
    var retryText by remember { mutableStateOf("") }

    // Keep the numeric field in sync with loaded/persisted values.
    LaunchedEffect(state.retryCount) { retryText = state.retryCount.toString() }
    // The secret is write-only: clear it as soon as it has been persisted.
    LaunchedEffect(state.isSaved) { if (state.isSaved) secret = "" }

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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text =
                    "El secreto se genera en el NAS (32+ bytes) y se guarda " +
                        "cifrado en el dispositivo. La URL no debe incluir " +
                        "credenciales, query ni fragment.",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.save(secret) }) { Text("Guardar") }
                OutlinedButton(
                    onClick = {
                        viewModel.disconnect()
                        secret = ""
                    },
                ) {
                    Text("Desconectar")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}
