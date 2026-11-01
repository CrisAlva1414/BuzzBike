package com.buzzbike.app.domain.wear

/**
 * Componentes de la bici que se desgastan y se trackean en v1.
 *
 * El conjunto sale de ADR-004:56-63 (cadena, suspensión, neumáticos y frenos).
 * Agregar un componente nuevo es configuración, no código nuevo de dominio
 * (ADR-004, sección "Consecuencias").
 */
enum class WearType {
    CHAIN,
    SUSPENSION,
    TIRES,
    BRAKES,
}

/**
 * Eje por el que se acumula uso y se calcula el desgaste.
 *
 * La fórmula de ADR-004:29-38 combina estos tres ejes y toma el máximo
 * normalizado.
 */
enum class WearAxis {
    KM,
    HOURS,
    DAYS,
}

/**
 * Ciclo de vida configurado para un componente.
 *
 * Cada eje es opcional: `null` significa que ese eje **no se evalúa** para el
 * componente (por ejemplo, una cadena solo declara [lifecycleKm]). Un
 * componente sin ningún eje configurado no tiene ciclo de vida y se representa
 * con `null` en lugar de esta clase (ver ADR-004:56-63: los frenos no tienen
 * default).
 *
 * @throws IllegalArgumentException si todos los ejes son `null` o si algún eje
 *   declarado no es estrictamente positivo. NaN también se rechaza porque no
 *   es `> 0`; infinito se acepta (equivale a un ciclo inagotable y el motor lo
 *   satura de forma segura).
 */
data class WearLifecycle(
    val lifecycleKm: Double? = null,
    val lifecycleHours: Double? = null,
    val lifecycleDays: Double? = null,
) {
    init {
        val declared = listOfNotNull(lifecycleKm, lifecycleHours, lifecycleDays)
        require(declared.isNotEmpty()) {
            "WearLifecycle requires at least one lifecycle axis"
        }
        require(declared.all { it > MIN_POSITIVE_AXIS }) {
            "WearLifecycle axes must be greater than zero"
        }
    }
}

/**
 * Uso acumulado del componente desde el último service.
 *
 * Todos los ejes son no negativos: un acumulado negativo indicaría un error de
 * datos, no un caso de negocio. Se permite infinito porque un acumulado
 * corrupto o desbordado debe saturarse de forma segura en el motor, no
 * romper el cálculo.
 *
 * @throws IllegalArgumentException si algún acumulado es negativo o NaN.
 */
data class ComponentUsage(
    val km: Double,
    val hours: Double,
    val days: Double,
) {
    init {
        require(km >= MIN_USAGE && hours >= MIN_USAGE && days >= MIN_USAGE) {
            "ComponentUsage axes must be non-negative"
        }
    }
}

/**
 * Estado de desgaste derivado del ratio y los umbrales de ADR-004:44-45.
 *
 * - [OK]: por debajo de 80% (o sin ejes evaluables).
 * - [APPROACHING]: `wearRatio >= 0.80`.
 * - [DUE]: `wearRatio >= 1.00` (ciclo excedido o vencido).
 */
enum class WearStatus {
    OK,
    APPROACHING,
    DUE,
}

/**
 * Resultado de evaluar un componente con [WearEngine].
 *
 * @property ratio `wearRatio` normalizado (ADR-004:29-38), o `null` cuando el
 *   componente no tiene ejes evaluables (sin ciclo de vida configurado).
 * @property status estado derivado de [ratio] y los umbrales 0.80/1.00.
 * @property limitingAxis eje que produjo el máximo; `null` si no hay ejes
 *   evaluables.
 */
data class WearAssessment(
    val ratio: Double?,
    val status: WearStatus,
    val limitingAxis: WearAxis?,
)

/** Menor ciclo de vida válido por eje; `0` no es un ciclo de vida. */
private const val MIN_POSITIVE_AXIS = 0.0

/** Piso de los acumulados de uso. */
private const val MIN_USAGE = 0.0
