package com.buzzbike.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.buzzbike.app.ui.theme.BuzzBikeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Compose UI.
 *
 * Slice 1: scaffold only — no navigation graph, no ride screens, no FGS.
 * The launcher intent-filter lives in AndroidManifest.xml.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BuzzBikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScaffoldPlaceholder(
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * Minimal placeholder content so the Compose pipeline (theme + Material3)
 * is visibly exercised. Replaced by the real ride screens in later slices.
 */
@Composable
private fun ScaffoldPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "BuzzBike",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
