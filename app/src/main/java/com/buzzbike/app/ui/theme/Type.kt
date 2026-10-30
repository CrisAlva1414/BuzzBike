package com.buzzbike.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.buzzbike.app.R

/**
 * Tipografía de marca. Los TTF se EMPAQUETAN en res/font (licencia OFL). NO usar Downloadable Fonts:
 * dependen de Google Play Services y piden red, lo que rompe local-first (AGENTS.md §7).
 *
 * Archivos requeridos en app/src/main/res/font/ (nombres en minúscula y con guion bajo):
 *   anton_regular.ttf
 *   darker_grotesque_variable.ttf   (el archivo variable [wght] de Google Fonts)
 *   jetbrains_mono_regular.ttf, jetbrains_mono_bold.ttf
 *
 * Reglas de marca:
 * - Anton: un solo peso, SIEMPRE en mayúsculas ([BuzzDisplay] lo aplica), nunca bajo 22 sp.
 * - Darker Grotesque: cuerpo y etiquetas. Rinde pequeña: piso de 18 sp, peso 600 o más.
 * - JetBrains Mono: solo tokens, URL, ids y bloques de datos. Piso de 14 sp.
 */
val AntonFamily = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))

// Si en tu versión de Compose ExperimentalTextApi ya es estable, quitá el @OptIn.
@OptIn(ExperimentalTextApi::class)
private fun darker(weight: FontWeight) =
    Font(
        resId = R.font.darker_grotesque_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

val DarkerGrotesqueFamily =
    FontFamily(
        darker(FontWeight.Normal),
        darker(FontWeight.Medium),
        darker(FontWeight.SemiBold),
        darker(FontWeight.Bold),
        darker(FontWeight.ExtraBold),
        darker(FontWeight.Black),
    )

val JetBrainsMonoFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )

private fun anton(
    size: Int,
    lineHeight: Double = 0.95,
) = TextStyle(
    fontFamily = AntonFamily,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = 0.01.sp,
)

private fun body(
    size: Int,
    weight: FontWeight,
) = TextStyle(
    fontFamily = DarkerGrotesqueFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.3).sp,
)

/** Estilo para tokens y datos carácter a carácter. */
val BuzzMono =
    TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

/**
 * Escala (idéntica al prototipo):
 *  displayLarge  112 sp  velocidad en vivo
 *  displayMedium  56 sp  odómetro de la bici
 *  headlineLarge  44 sp  título de pantalla y cifras de métrica
 *  headlineMedium 32 sp  cifras secundarias
 *  headlineSmall  26 sp  nombre de ride / componente
 *  titleLarge     24 sp  texto de botón
 */
internal val BuzzTypography =
    Typography(
        displayLarge = anton(112, 0.85),
        displayMedium = anton(56),
        displaySmall = anton(44),
        headlineLarge = anton(44),
        headlineMedium = anton(32),
        headlineSmall = anton(26),
        titleLarge = anton(24),
        titleMedium = body(20, FontWeight.ExtraBold),
        titleSmall = body(19, FontWeight.ExtraBold),
        bodyLarge = body(19, FontWeight.SemiBold),
        bodyMedium = body(18, FontWeight.SemiBold),
        bodySmall = body(18, FontWeight.SemiBold), // sin 12 sp: piso de 18 en Darker Grotesque
        labelLarge = body(19, FontWeight.ExtraBold),
        labelMedium = body(18, FontWeight.Bold),
        labelSmall = body(18, FontWeight.Bold),
    )
