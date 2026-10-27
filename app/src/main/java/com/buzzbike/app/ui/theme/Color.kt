package com.buzzbike.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Color schemes — Material 3 defaults.
 *
 * TODO(design): replace with the BuzzBike brand palette once design exists
 * (AGENTS.md §11). Scaffold uses stock M3 light/dark schemes: no invented
 * brand colors, no hardcoded image assets.
 *
 * Wear alerts (80%/100%, ADR-004) will need distinguishable colors that do
 * not rely on hue alone (accessibility, estilo §7) — that belongs to the
 * wear UI slice, not this scaffold.
 */

internal val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF2E7D32),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB9F6CA),
        onPrimaryContainer = Color(0xFF0B3D12),
        secondary = Color(0xFF4E6E51),
        secondaryContainer = Color(0xFFD0F1D5),
        onSecondaryContainer = Color(0xFF0C1F10),
        background = Color(0xFFFBFDF7),
        onBackground = Color(0xFF1A1C1A),
        surface = Color(0xFFFBFDF7),
        onSurface = Color(0xFF1A1C1A),
        surfaceVariant = Color(0xFFDDE5DA),
        onSurfaceVariant = Color(0xFF424940),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

internal val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF9DD69F),
        onPrimary = Color(0xFF0B3D12),
        primaryContainer = Color(0xFF1B5E20),
        onPrimaryContainer = Color(0xFFB9F6CA),
        secondary = Color(0xFFB4D8B8),
        secondaryContainer = Color(0xFF365239),
        onSecondaryContainer = Color(0xFFD0F1D5),
        background = Color(0xFF111410),
        onBackground = Color(0xFFE2E3DE),
        surface = Color(0xFF111410),
        onSurface = Color(0xFFE2E3DE),
        surfaceVariant = Color(0xFF424940),
        onSurfaceVariant = Color(0xFFC2C9BE),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
