package com.buzzbike.app.domain.wear

/**
 * Defaults de ciclo de vida de v1 (ADR-004:56-63).
 *
 * Son **defaults, no verdades de fábrica** (ADR-004:65): el usuario debe poder
 * ajustarlos por componente y por bici. La UI tiene que mostrar el default
 * usado y permitir editarlo.
 */
object ComponentDefaults {
    /** Cadena: 2000 km por eje de distancia (ADR-004:58). */
    const val CHAIN_LIFECYCLE_KM: Double = 2000.0

    /** Suspensión: 100 h por eje de horas (ADR-004:59). */
    const val SUSPENSION_LIFECYCLE_HOURS: Double = 100.0

    /**
     * Neumáticos: default de 3000 km, el mínimo del rango configurable
     * 3000–5000 km de ADR-004:60. Es un default editable, no un máximo de
     * fábrica.
     */
    const val TIRES_LIFECYCLE_KM: Double = 3000.0

    /**
     * Ciclo de vida por defecto para [type].
     *
     * - [WearType.CHAIN]: 2000 km.
     * - [WearType.SUSPENSION]: 100 h.
     * - [WearType.TIRES]: 3000 km (mínimo del rango 3000–5000 km).
     * - [WearType.BRAKES]: `null`. ADR-004:61-63 define un proxy
     *   km + desnivel negativo sin factor fijo; el factor y el ciclo quedan
     *   como TODO configurable. Por eso no se inventa un default: requiere
     *   configuración explícita del usuario.
     */
    fun lifecycleFor(type: WearType): WearLifecycle? =
        when (type) {
            WearType.CHAIN -> WearLifecycle(lifecycleKm = CHAIN_LIFECYCLE_KM)
            WearType.SUSPENSION -> WearLifecycle(lifecycleHours = SUSPENSION_LIFECYCLE_HOURS)
            WearType.TIRES -> WearLifecycle(lifecycleKm = TIRES_LIFECYCLE_KM)
            WearType.BRAKES -> null
        }
}
