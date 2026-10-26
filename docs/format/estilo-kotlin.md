# Estilo Kotlin / Compose — BuzzBike

Convenciones de código para todo el proyecto. El formatter/linter de referencia es **ktlint** (formato oficial Kotlin); el analyzer es **detekt**. Lo que sigue es la capa humana: naming, estructura y decisiones que ktlint no impone.

---

## 1. Naming

| Qué | Estilo | Ejemplo |
|---|---|---|
| Clases, interfaces, objetos, enums | PascalCase | `RideRepository`, `CalorieCascade`, `RideUiState` |
| Composables | PascalCase | `RideScreen`, `WearAlertCard` |
| Funciones y props de Compose | camelCase | `calculateCalories()`, `onStartRide()` |
| Variables y propiedades | camelCase | `movingTimeSeconds`, `wearRatio` |
| Constantes (top-level o companion) | SCREAMING_SNAKE_CASE | `MET_ROAD_FAST`, `DEFAULT_CHAIN_LIFECYCLE_KM` |
| Parámetros de Compose que son lambdas | Prefijo `on` | `onNavigateBack`, `onServiceLogged` |
| Prefijo de utilidades de test | `given` / `when` / `then` en nombres de test | `given_speedBands_when_high_then_compendiumMet()` |
| Package names | lowercase, sin underscores | `com.buzzbike.app.domain.calorie` |

---

## 2. Estructura de un Composable

- **Un Composable = una responsabilidad visual.** Si un `RideScreen` tiene más de ~80 líneas, extraer sub-Composables.
- **State hoisting:** el estado vive arriba; los Composables hoisted reciben estado + lambdas de callback. No leer estado global dentro de Composables profundos.
- **`RideUiState` es un `sealed interface`** (ver §4). El Composable hace `when (state)` exhaustivo.
- **Sin lógica de negocio en Composables.** El cálculo vive en `domain/` o en el ViewModel. El Composable solo compone.
- **Preview functions** para los componentes visuales puros (cards, rows). Los previews no dependen de Room ni de sensores.

Ejemplo de forma esperada:

```kotlin
@Composable
fun RideScreen(
    state: RideUiState,
    onStartRide: () -> Unit,
    onStopRide: () -> Unit,
    onOpenComponentDetail: (String) -> Unit,
) {
    when (state) {
        is RideUiState.Idle -> IdleContent(onStartRide = onStartRide)
        is RideUiState.Tracking -> TrackingContent(
            state = state,
            onStopRide = onStopRide,
        )
        is RideUiState.Summary -> SummaryContent(
            state = state,
            onOpenComponentDetail = onOpenComponentDetail,
        )
    }
}
```

---

## 3. Null safety

- Preferir **tipos no-null** y `?` explícito solo cuando el null es un caso de negocio real (sensor ausente, GPS sin fix).
- **Evitar `!!`.** Si se usa, justificación obligatoria en comentario + issue/TODO. Detekt puede marcarlo.
- **Evitar `lateinit`** salvo que la inicialización esté garantizada por el ciclo de vida (p. ej. inyección de Hilt en un `@HiltViewModel`). Para el resto, usar nullable + inicialización perezosa o constructores.
- `String?` para inputs de usuario que pueden venir vacíos; no usar `String` non-null con `""` como "vacío".

---

## 4. Estados de UI: sealed interfaces

Todo estado de pantalla es un `sealed interface` (o `sealed class` si hay herencia de datos). **Prohibido** representar estados con `Boolean` sueltos o con flags mutables.

```kotlin
sealed interface RideUiState {
    data object Idle : RideUiState

    data class Tracking(
        val distanceMeters: Double,
        val speedMps: Double,
        val elapsedSeconds: Long,
        val movingSeconds: Long,
        val elevationGainM: Double,
        val liveCalorieEstimate: CalorieEstimate,
    ) : RideUiState

    data class Summary(
        val ride: Ride,
        val calorieEstimate: CalorieEstimate,
        val wearSnapshot: List<ComponentWear>,
        val syncState: SyncState,
    ) : RideUiState
}
```

Reglas:

- `when` siempre **exhaustivo** (el compilador lo garantiza con sealed).
- Agregar un estado nuevo = agregar una rama en todos los `when` que consumen ese sealed. No se ignora un estado.
- Los estados de error de dominio NO se mezclan con los estados de UI de happy path; se modelan aparte (ver §5).

---

## 5. Errores de dominio

- **Errores de negocio esperados** (GPS sin fix, sensor ausente, payload demasiado grande, HMAC rechazado): modelar con `Result<T>` de Kotlin o con un `sealed class` propio del dominio.

```kotlin
sealed class CalorieCalcResult {
    data class Success(
        val estimate: CalorieEstimate, // kcal + intervalo + modelo usado
    ) : CalorieCalcResult()

    data class InsufficientData(
        val reason: String, // p. ej. "no hay velocidad ni HR ni potencia"
    ) : CalorieCalcResult()
}
```

- **Exceptions** solo para bugs (programming errors): invariantes rotos, acceso a estado imposible. **Nunca** para controlar flujos de negocio esperados.
- En `data/`, errores de red/Room se mapean a tipos de dominio antes de subir a `ui/`. La UI no debe conocer `IOException` ni `SQLiteException`.

---

## 6. Coroutines y Flow

- **`suspend` para IO/red/Room.** Nunca bloquear el main thread. Si una función toca disco o red, es `suspend` o corre en `Dispatchers.IO` explícito.
- **`Flow` para streams** de sensores (`sensor/` emite `Flow<SensorReading>`), estado de UI (ViewModel expone `StateFlow<RideUiState>`), y cambios de Room (`Flow` desde DAOs).
- **`StateFlow` / `SharedFlow`** para estado observable; `callbackFlow` solo como bridge de APIs de callback (p. ej. FusedLocationProvider).
- **Scope de corrutinas:** los ViewModels usan `viewModelScope`. Los WorkManagers usan su propio scope. No crear `CoroutineScope` global a mano salvo caso de app con justificación.
- **Estructuradas concurrency:** `async/await` para paralelismo real; no `GlobalScope.launch`.
- **Error handling:** usar `try/catch` en la capa que puede recuperar; `Result` o sealed para errores de dominio que suben a UI. `CoroutineExceptionHandler` como última red.

---

## 7. Compose specifics

- **Material 3** es el design system. No importar Material 2.
- **Sin `remember { }` para lógica de negocio.** Solo para derivados baratos de UI.
- **`key()`** cuando hay listas con identidad estable y se reordena.
- **Accesibilidad:** contentDescription en iconos; tamaños de touch ≥ 48dp; contraste suficiente. Los umbrales de wear (80%/100%) deben ser distinguishables también sin depender solo del color.
- **Estados de carga/empty/error** modelados en el sealed state, no con flags paralelos.

---

## 8. Patrones de dominio (específicos de BuzzBike)

- **Fórmulas calóricas y wear ratios** en `domain/`, JVM puro, con **KDoc que cite la fuente** (paper/compendium). Ver ADR-001 y ADR-004. Un cambio de constante sin cita = bloqueado en review.
- **Modelos de datos de ride** como `data class` inmutables. Room entities separadas de domain models; mapper en `data/`.
- **`bike_id` / `component_id`** como UUID strings estables; no IDs incrementales compartidos entre dispositivos.
- **Nada de Android imports en `domain/`.** Si aparece `android.*` en `domain/`, es un bug de arquitectura.

---

## 9. Tests

- `domain/` se testea en **JVM puro** (sin Robolectric, sin instrumented).
- Tests de calorías = **snapshot de valores conocidos** (fixtures con la fórmula citada) + caso límite.
- Tests de wear = dado un acumulado y unos thresholds, assert del `wearRatio` y del evento (`approaching`/`due`).
- Naming de tests: `given_<setup>_when_<action>_then_<expected>()` o el estilo `should_*` — elegir uno por módulo y mantenerlo.

---

## 10. Lo que NO se hace

- No se usa `!!` sin justificación.
- No se mezclan estados de UI en `Boolean` mutables.
- No se bloquea el main thread.
- No se inventan constantes fisiológicas sin fuente.
- No se presenta un número calórico como exacto en la UI.
- No se importa Material 2 ni layouts XML para features nuevas.
- No se envía telemetría ni PII a terceros.
