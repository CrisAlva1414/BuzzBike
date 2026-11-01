package com.buzzbike.app.domain.wear

/**
 * Motor puro de desgaste por máximos normalizados (ADR-004:29-38).
 *
 * Fórmula exacta:
 *
 * ```
 * wearRatio = max(
 *     km    / lifecycleKm,
 *     hours / lifecycleHours,
 *     days  / lifecycleDays
 * )
 * ```
 *
 * Se toman solo los ejes declarados en [WearLifecycle]; un eje `null` no se
 * evalúa. El componente se considera gastado en el primer eje que se agota:
 * `wearRatio > 1` significa ciclo excedido (ADR-004:37-38).
 *
 * Umbrales globales, sin variación por componente (ADR-004:44-45):
 * - [APPROACHING_THRESHOLD] (`>= 0.80`) → [WearStatus.APPROACHING].
 * - [DUE_THRESHOLD] (`>= 1.00`) → [WearStatus.DUE].
 *
 * Sin ejes evaluables, el resultado es [WearStatus.OK] con `ratio` y
 * `limitingAxis` en `null`: no hay nada que alertar.
 *
 * Seguridad numérica: un ratio `NaN` (solo posible con infinito/infinito) se
 * descarta como eje no evaluable; un ratio infinito se satura a
 * [Double.MAX_VALUE], que cae en [WearStatus.DUE]. Así ningún valor no finito
 * produce un estado inesperado ni propaga `NaN` al llamador.
 */
object WearEngine {
    /** Límite inferior de "se acerca el cambio" (ADR-004:44). */
    const val APPROACHING_THRESHOLD: Double = 0.80

    /** Límite inferior de "toca cambiar" (ADR-004:45). */
    const val DUE_THRESHOLD: Double = 1.00

    /**
     * Evalúa [usage] contra [lifecycle].
     *
     * @param lifecycle ciclo configurado del componente, o `null` si el
     *   componente aún no tiene ciclos definidos (p. ej. frenos por defecto).
     * @param usage acumulado de uso desde el último service.
     * @return ratio, estado y eje limitante; ratio/axis `null` sin ejes
     *   evaluables.
     */
    fun assess(
        lifecycle: WearLifecycle?,
        usage: ComponentUsage,
    ): WearAssessment {
        val axes = lifecycle?.let { evaluateAxes(it, usage) }.orEmpty()
        val limiting = axes.maxByOrNull { it.second }
        val ratio = limiting?.second

        return WearAssessment(
            ratio = ratio,
            status = statusFor(ratio),
            limitingAxis = limiting?.first,
        )
    }

    /**
     * Construye los ratios evaluables en orden [WearAxis.KM], [WearAxis.HOURS],
     * [WearAxis.DAYS]. En caso de empate, [maxByOrNull] conserva el primero de
     * ese orden.
     */
    private fun evaluateAxes(
        lifecycle: WearLifecycle,
        usage: ComponentUsage,
    ): List<Pair<WearAxis, Double>> =
        listOfNotNull(
            ratioOrNull(WearAxis.KM, usage.km, lifecycle.lifecycleKm),
            ratioOrNull(WearAxis.HOURS, usage.hours, lifecycle.lifecycleHours),
            ratioOrNull(WearAxis.DAYS, usage.days, lifecycle.lifecycleDays),
        )

    /**
     * Ratio de un eje, o `null` si el eje no está declarado o no es evaluable.
     *
     * `coerceAtMost` satura `+Infinity` a [Double.MAX_VALUE]; `NaN` se filtra
     * antes para no propagarlo.
     */
    private fun ratioOrNull(
        axis: WearAxis,
        used: Double,
        lifecycle: Double?,
    ): Pair<WearAxis, Double>? =
        lifecycle
            ?.let { used / it }
            ?.takeUnless { it.isNaN() }
            ?.let { axis to it.coerceAtMost(Double.MAX_VALUE) }

    private fun statusFor(ratio: Double?): WearStatus =
        when {
            ratio == null -> WearStatus.OK
            ratio >= DUE_THRESHOLD -> WearStatus.DUE
            ratio >= APPROACHING_THRESHOLD -> WearStatus.APPROACHING
            else -> WearStatus.OK
        }
}
