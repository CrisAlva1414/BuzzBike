> Archivado el 2026-10-30 por el orchestrator. Motivo: tarea U1 "Ride en vivo"
> **completada** (commits `fcea1ab` y `bcf20ec`, gate PASS). La persona aprobó el
> plan del permiso de ubicación en runtime y pidió dejarlo encolado: la tarea
> siguiente (U1.1) arranca en `state/loop-state.md` con ese plan aprobado.

# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Tarea previa (Iterations 0–39): archivada en
> `state/archive/loop-state-2026-10-30-handoff-u0-l1.md`.
> Referencia de diseño de esta tarea:
> `/home/pc01/Escritorio/buzzbike-handoff/android-handoff/ANDROID_HANDOFF.md` §5 U1.

---

## Tarea activa — U1: Ride en vivo

### Pedido
Continuar el plan de UI del handoff: implementar **U1 (Ride en vivo)** sobre el
repo en `main` (`64faa25`). U0 (tema/componentes) y L1 (pause/resume + FGS) ya
están cerrados con gate PASS.

### Clase de triage
**Standard.** Código de aplicación (Compose + dominio de sesión) con slices y
gates `tester` + `security-reviewer`. No es Fast: toca FGS/permisos de
ubicación en su superficie de UI y es un slice de producto. No es Heavy: no
agrega auth, secretos, esquema ni dependencias nuevas; **si `builder` necesita
tocar el manifest, permisos o el esquema Room, se detiene y escala** (pasa a
Heavy con aprobación humana).

### Criterio de aceptación
U1 se cierra cuando se cumplen **todos**:

1. `RideScreen` renderiza los cinco estados del handoff — `Idle`, `Ready`,
   `Recording`, `Paused`, `Finished`, `Recoverable` — con:
   - calorías **siempre** como banda (`≈ min–max kcal` + modelo), nunca un
     número exacto (ADR-001 / AGENTS.md §6.4);
   - franja de pausa automática cuando `autoPaused` es `true`;
   - `HoldToStopButton` para detener (no tap simple).
2. El estado de sesión vive en `domain/ride/session/` como `sealed interface`
   JVM-puro, y el controlador cubre `start`, `pause`, `resume`, `finish`,
   `discard` y `recovery` con tests JVM (sin Robolectric).
3. `autoPaused` se deriva de `MovingTimeEstimator` / `RideParameters`
   (ADR-005: `V_STOP_MPS 0.4`/`T_STOP_SECONDS 10`, `V_GO_MPS 0.8`/
   `T_GO_SECONDS 3`). Prohibido inventar umbrales nuevos.
4. `FLAG_KEEP_SCREEN_ON` solo mientras se graba (o pausa) y se libera al salir.
5. Gate verde: `./gradlew test ktlintCheck detekt assembleDebug lintDebug` y
   `./gradlew assembleDebugAndroidTest`.
6. `tester: PASS` y `security-reviewer: PASS` sobre el diff final.

### Fuera de alcance (queda para U2–U6)
- Mapa MapLibre (U5). En U1 la traza se dibuja en un `Canvas` simple o se omite.
- Historial/detalle, GPX y la regla de reloj de pared monotónico (U2, laguna §5.5).
- Garaje/bici real (`bikes`/`components`) y `WearEngine` (U4). El selector de
  bici queda detrás de un placeholder sin esquema nuevo.
- DataStore para tema persistido (U3).
- Cambios de manifest/permisos/FGS tipo `health`: **bloqueado**, requiere
  aprobación humana.
- Migraciones Room / columnas nuevas.

### Plan de slices
| # | Slice | Alcance | Archivos (aprox.) |
|---|---|---|---|
| A | Dominio de sesión | `RideSessionState` sellado + `LiveMetrics`/`CalorieBand`/`GpsStatus`; detector de auto-pausa sobre `MovingTimeEstimator`; `discard` y detección de `Recoverable` en el controlador + puertos; tests JVM | `domain/ride/session/*`, `data/repository/RideRepository.kt`, tests |
| B | Formateo + componentes | Helpers puros de formato (km, km/h, mm:ss, banda calórica) con tests; `MetricCell`, `BuzzButton`, `StatusBanner` en `ui/components` | `ui/ride/*Format*.kt`, `ui/components`, tests |
| C | Pantalla + ViewModel | `RideScreen` con los 5 estados, `HoldToStopButton`, keep-screen-on, diálogo de recuperación, `RideViewModel` adaptado | `ui/ride/*`, `MainActivity`/nav solo si hace falta |
| D | Cierre | gate completo, docs (`AGENTS.md` §0, `docs/roadmap.md`, handoff), commit + changelog | docs/state |

### Iteraciones
| Iter | Fecha | Slice | Resultado | Notas |
|---|---|---|---|---|
| 1 | 2026-10-30 | A | gate PASS (tras fixes) | `RideSessionState` sellado + auto-pausa ADR-005 + `discard`/`restore`. Security: 1 media (doble `startRide`) + 4 bajas. |
| 2 | 2026-10-30 | A (fixes) | PASS | `Mutex` en transiciones, guard de `rideId` en `TrackingManager`, `@Transaction` en `replaceActiveRide`/`deleteRideWithPoints`, `cancelAndJoin`, `resumeRide` respeta permiso, `try/finally` del FGS. Security: F1/F3/F5 cerrados, F2 parcial (baja). |
| 3 | 2026-10-30 | B | PASS | `RideFormat` (km, km/h, duración, altitud, banda calórica) + `MetricCell`/`BuzzButton`/`StatusBanner`. 312 tests. |
| 4 | 2026-10-30 | C1 | PASS | `RideSessionWriter.loadTrackPoints`, `salvageRecoverableRide` (procesa puntos persistidos → `Finished`; sin puntos → borra y `Ready`), `restore()` solo desde `Idle`, `init` del ViewModel. 322 tests. |
| 5 | 2026-10-30 | C2 | PASS | `RideScreen` con 6 estados, `RideTrace` (Canvas), keep-screen-on restaurado, doble confirmación de descarte, diálogo de recuperación no cerrable, botón de inicio deshabilitado sin permiso. Lint/format/detekt verdes. |
| 6 | 2026-10-30 | D | PASS | Docs (`AGENTS.md` §0/§4, `docs/roadmap.md` §1, sesión, `CHANGELOG.md`) + commit `fcea1ab`. Gate completo verde. |

### Deuda abierta al cerrar A y B (baja, no bloqueante)
- F2 residual: `TrackingManager.stopTracking()` no hace `join()` del `trackingJob`; un
  `saveTrackPoint` en vuelo podría commitear después del delete (sin FK/cascade).
  Fix acotado pendiente: `stopTrackingAndJoin()`.
- `refreshReady()` y `acknowledgeFinished()` escriben estado fuera de `lifecycleMutex`.
- `stopRide` del ViewModel puede salir temprano si `stateIn` aún no propagó → FGS vivo
  hasta el siguiente toque. Derivar el id del manager o mover el guard dentro de la corrutina.
- Cobertura: falta test manager-level de parada <10 s y de "stop lanza excepción → service.stop()".
- TalkBack: `MetricCell`/`StatusBanner` duplican anuncio (contentDescription en contenedor
  sin `clearAndSetSemantics`/`mergeDescendants`).
- `RideFormat`: faltan casos de entrada negativa/NaN en distancia, `low==high`, locale default hostil.
- Atomicidad real de `@Transaction` en default methods de DAO no ejercitada con Room in-memory.

### Decisiones
- Se adopta el contrato sellado del handoff (§5 U1) en lugar de seguir
  engordando el `RideSessionUiState` plano: da `when` exhaustivo en la UI y
  hace testeable el estado de recuperación en JVM.
- El `TrackingService` sigue siendo propiedad del `RideViewModel` (decisión de
  L1); U1 **no** mueve el servicio al controlador. El controlador no conoce
  Android.
- Si el diff de A obliga a tocar esquema Room o manifest → STOP y escalado.

### Riesgos conocidos
- Sin emulador ni dispositivo: la UI Compose solo se verifica compilando
  (`assembleDebug` + `assembleDebugAndroidTest`). Lista de verificación manual
  en `docs/sessions/` al cerrar D.
- Laguna §5.4 (tick en vivo): hoy se reprocesa a 1 Hz; en U1 se mantiene y se
  deja anotado como deuda de performance hasta medir en dispositivo.

### Estado
**completada** — 2026-10-30, commit `fcea1ab`.

### Gate final
| Check | Resultado |
|---|---|
| `test` | 322 tests, 0 fallos, 0 skipped |
| `ktlintCheck` / `detekt` | PASS |
| `assembleDebug` / `lintDebug` | PASS |
| `assembleDebugAndroidTest` | compila |
| `tester` | PASS (A, B, C2) |
| `security-reviewer` | PASS (A con fixes, B, C2 sin hallazgos) |

### Pendiente de aprobación humana
- **Pedido de permiso de ubicación en runtime**: sin él, en una instalación
  nueva `startRide()` siempre devuelve `Ready(NO_PERMISSION)` y el botón queda
  deshabilitado. Es superficie de permisos → Heavy → requiere aprobación
  explícita antes de que `builder` toque una línea. Propuesta: usar
  `rememberLauncherForActivityResult(RequestMultiplePermissions)` para
  `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` en `Ready(NO_PERMISSION)`,
  sin tocar el manifest (ya declara ambos).
