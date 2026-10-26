package com.buzzbike.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * BuzzBike Material 3 theme.
 *
 * Dark theme follows the system setting. No dynamic color (Android 12+)
 * in the scaffold — TODO(design) once brand palette is defined; dynamic
 * color would fight a fixed brand identity.
 */
@Composable
fun BuzzBikeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
