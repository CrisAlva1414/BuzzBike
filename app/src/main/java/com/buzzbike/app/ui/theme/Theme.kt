package com.buzzbike.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

enum class ThemeMode { SOL, NOCHE, AUTO }

object BuzzDimens {
    /** Borde neobrutalista: sólido y grueso, nunca de 1 dp. */
    val Border = 3.dp

    /** Objetivo táctil mínimo (guantes, una mano). Controles principales del Ride: 84 dp. */
    val MinTouch = 56.dp
    val RideActionHeight = 84.dp
}

private val LocalBuzzColors = staticCompositionLocalOf { SolColors }

object BuzzTheme {
    val colors: BuzzColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBuzzColors.current
}

/** Esquinas rectas en todo M3: es parte de la identidad. */
private val SquareShapes =
    Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(0.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )

@Composable
fun BuzzBikeTheme(
    mode: ThemeMode = ThemeMode.SOL,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.SOL -> false
            ThemeMode.NOCHE -> true
            ThemeMode.AUTO -> isSystemInDarkTheme()
        }
    val colors = if (dark) NocheColors else SolColors
    CompositionLocalProvider(LocalBuzzColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toM3(dark),
            typography = BuzzTypography,
            shapes = SquareShapes,
            content = content,
        )
    }
}
