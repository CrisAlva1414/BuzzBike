@file:Suppress("MatchingDeclarationName")

package com.buzzbike.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Tokens de color de BuzzBike (fuente: prototipo web, src/index.css).
 *
 * Reglas:
 * - Amarillo ([BuzzColors.accent]) SOLO como relleno con [BuzzColors.onAccent]. Nunca como texto
 *   sobre fondo claro (no pasa contraste). Negro sobre amarillo: ~12:1.
 * - Ningún estado depende solo del color: alertas 80 %/100 % llevan icono + texto (ADR-004, estilo §7).
 * - Sin dynamic color de Material You: chocaría con la identidad de marca.
 */
@Immutable
data class BuzzColors(
    val ground: Color,
    val ink: Color,
    val accent: Color,
    val onAccent: Color,
    val panel: Color,
    val danger: Color,
    val onDanger: Color,
    val mapLand: Color,
    val mapPark: Color,
    val mapWater: Color,
    val mapRoad: Color,
)

/** "Sol": claro, alto contraste, pensado para pedalear a pleno día. Tema por defecto. */
val SolColors =
    BuzzColors(
        ground = Color(0xFFF6F4EC),
        ink = Color(0xFF111111),
        accent = Color(0xFFFFC400),
        onAccent = Color(0xFF111111),
        panel = Color(0xFFE6E2D2),
        danger = Color(0xFFC1121F),
        onDanger = Color(0xFFFFFFFF),
        mapLand = Color(0xFFE6E2D2),
        mapPark = Color(0xFFCBDDAE),
        mapWater = Color(0xFFA8CBE2),
        mapRoad = Color(0xFFFFFFFF),
    )

/** "Noche": mismo acento sobre fondo casi negro. */
val NocheColors =
    BuzzColors(
        ground = Color(0xFF0B0B0B),
        ink = Color(0xFFF6F4EC),
        accent = Color(0xFFFFC400),
        onAccent = Color(0xFF111111),
        panel = Color(0xFF1D1D1B),
        danger = Color(0xFFD62839),
        onDanger = Color(0xFFFFFFFF),
        mapLand = Color(0xFF1D1D1B),
        mapPark = Color(0xFF26331F),
        mapWater = Color(0xFF15293A),
        mapRoad = Color(0xFF3B3B36),
    )

/** Mapeo a Material 3 para que los componentes M3 hereden la marca sin tintes de superficie. */
internal fun BuzzColors.toM3(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = ink,
            onSecondary = ground,
            background = ground,
            onBackground = ink,
            surface = ground,
            onSurface = ink,
            surfaceVariant = panel,
            onSurfaceVariant = ink,
            surfaceContainerLowest = ground,
            surfaceContainerLow = panel,
            surfaceContainer = panel,
            surfaceContainerHigh = panel,
            surfaceContainerHighest = panel,
            outline = ink,
            error = danger,
            onError = onDanger,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = ink,
            onSecondary = ground,
            background = ground,
            onBackground = ink,
            surface = ground,
            onSurface = ink,
            surfaceVariant = panel,
            onSurfaceVariant = ink,
            surfaceContainerLowest = ground,
            surfaceContainerLow = panel,
            surfaceContainer = panel,
            surfaceContainerHigh = panel,
            surfaceContainerHighest = panel,
            outline = ink,
            error = danger,
            onError = onDanger,
        )
    }
