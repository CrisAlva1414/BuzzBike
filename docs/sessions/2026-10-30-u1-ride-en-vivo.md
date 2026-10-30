# Sesión 2026-10-30 — U1: Ride en vivo

- **Fecha:** 2026-10-30
- **Tipo:** Implementación (slice U1 — pantalla de ride en vivo)
- **Estado al cerrar:** **U1 implementado y cerrado con gate PASS.**
- **Gate:** `test ktlintCheck detekt assembleDebug lintDebug` y `assembleDebugAndroidTest`, en verde. **322 tests, 0 fallos, 0 skipped.**

---

## Objetivo

Tener una pantalla de ride en vivo usable de punta a punta sobre el motor ya
cerrado (captura, descriptores, elevación y calorías): iniciar, pausar,
reanudar y terminar un ride, ver métricas en vivo, y recuperar una sesión
interrumpida sin perder datos.

---

## Qué se implementó, por capa

### Dominio / sesión

- **`domain/ride/session/RideSessionState.kt`** — contrato sellado de la
  sesión: `Idle / Ready(gps, hasBarometer) / Recording(rideId, live, autoPaused)
  / Paused(rideId, live) / Finished(rideId, summary) / Recoverable(rideId)`,
  más `LiveMetrics`, `RideSummary`, `GpsStatus` y `LatLon`.
- **`RideSessionManager`** — `state: StateFlow<RideSessionState>`, `restore()`,
  `startRide(): String?` (el guard de permiso corre **antes** de crear la fila),
  `pause` / `resume` / `stop` / `discard` / `salvageRecoverableRide` /
  `acknowledgeFinished` / `refreshReady`. Transiciones serializadas con
  `Mutex`, colectores cerrados con `cancelAndJoin`, y banda calórica
  persistida completa.
- **Auto-pausa** derivada de `MovingTimeEstimator` / `RideParameters` (ADR-005)
  vía `MovingState.isMoving` y `stoppedSinceNanos`. **Sin umbrales nuevos.**
- **`TrackingManager`** — rechaza (`false`) un `rideId` distinto mientras hay
  una sesión activa.

### Datos

- **`RideSessionWriter` / `RideRepository` / `RideDao`** — `findActiveRideId`,
  `deleteRide`, `loadTrackPoints`; `replaceActiveRide` y `deleteRideWithPoints`
  anotados con `@Transaction`.

### UI

- **`RideFormat`** — formato de km, km/h, duración, elevación/altitud y banda
  calórica con el modelo de ADR-001 (nunca un número exacto).
- **`RideTrace`** — traza dibujada en `Canvas`, sin mapas.
- **`RideScreen`** — cubre los 6 estados del contrato.
- **Componentes** — `MetricCell`, `BuzzButton`, `StatusBanner`, `StatusTone`,
  `HoldToStopButton` para terminar (evita cortes accidentales).
- **Keep-screen-on** vía `LocalView.keepScreenOn`, activo solo en
  `Recording` / `Paused`, con restauración al salir.
- **Descarte** con doble confirmación.
- **Diálogo de recuperación** no cerrable por fuera ni por back.

---

## Decisiones y desviaciones respecto del handoff

- **Sin pedido de permiso de ubicación en runtime.** En `Ready(NO_PERMISSION)`
  el botón queda deshabilitado con aviso. **Pendiente de aprobación humana**
  (es una superficie de permisos).
- **Sin mapa MapLibre** (corresponde a U5): la traza es un `Canvas`.
- **Sin selector de bici** (U4, requiere esquema) ni **edición de nombre**
  (U2, requiere columna).
- **Sin barra inferior de 3 pestañas** (Historial/Garaje no existen todavía);
  se difiere a U2/U4.
- **`TrackingService` sigue en el `RideViewModel`**, decisión heredada de L1.

## Lagunas del handoff §5 que siguen abiertas

- **§5.4** — se reprocesa a 1 Hz; falta medir el costo en dispositivo real.
- **§5.5** — paso de monotónico a reloj de pared para GPX; pertenece a U2.

---

## Verificación

```bash
./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain
./gradlew assembleDebugAndroidTest --console=plain
```

Resultado: **verde**. `test` = **322 tests, 0 fallos, 0 skipped**;
`ktlintCheck`, `detekt`, `assembleDebug` y `lintDebug` en 0;
`assembleDebugAndroidTest` compila.

---

## Checklist de verificación manual en dispositivo

Sin emulador en la máquina de desarrollo, U1 queda **verificado por tests JVM y
build**, pero falta la pasada manual en hardware real:

- [ ] Primer arranque sin permiso de ubicación: `Ready(NO_PERMISSION)`, botón
      deshabilitado y aviso visible.
- [ ] Iniciar ride con permiso concedido: entra en `Recording` y el FGS queda
      activo (notificación presente).
- [ ] Métricas en vivo: distancia, velocidad, duración y banda calórica se
      actualizan sin saltos ni congelamientos.
- [ ] Pausa manual: estado `Paused`, métricas congeladas y FGS con el control de
      reanudar.
- [ ] Auto-pausa: al detenerse el movimiento cambia `autoPaused` sin pausar a
      mano; al retomar vuelve solo a `Recording`.
- [ ] Terminar con `HoldToStopButton`: mantiene el hold, pasa a `Finished` y el
      resumen muestra banda calórica con modelo e intervalo.
- [ ] Keep-screen-on: la pantalla permanece encendida en `Recording` / `Paused`
      y se restaura el comportamiento normal al salir.
- [ ] Recuperación: matar la app con un ride activo, reabrir y confirmar que el
      diálogo no cerrable permite salvar o descartar el ride interrumpido.
- [ ] Descarte: exige doble confirmación y borra el ride con sus puntos.

---

## Deuda y riesgos conocidos (baja)

- `TrackingManager.stopTracking()` no hace `join()` del `trackingJob` → posible
  punto huérfano tras `discard`.
- `refreshReady()` / `acknowledgeFinished()` escriben estado **fuera** del
  `Mutex`.
- `stopRide` del ViewModel puede salir temprano si el `StateFlow` no propagó →
  el FGS queda vivo hasta el siguiente toque.
- Faltan tests manager-level de parada <10 s y de "stop lanza excepción →
  `service.stop()`".
- Posible anuncio duplicado en TalkBack en `MetricCell` / `StatusBanner`.
- `RideFormat`: faltan casos negativos/NaN en distancia, `low == high` y locale
  default hostil.
- La atomicidad real de `@Transaction` en default methods de DAO no está
  ejercitada con Room in-memory.

## Handoff a la sesión siguiente

- **Próxima tarea:** **U1.1 — permiso de ubicación en runtime**, con plan ya
  aprobado por la persona (2026-10-30) y criterio de aceptación completo en
  `state/loop-state.md`.
- **Alcance:** acción "DAR PERMISO" en `Ready(NO_PERMISSION)` con
  `RequestMultiplePermissions` (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`),
  re-evaluación vía `RideSessionManager.refreshReady()` y helper puro testeable
  en `ui/ride/LocationPermissionUi.kt`. Sin tocar el manifest.
- **Después:** U2 — historial/detalle + GPX (ahí se cierra la laguna del reloj
  de pared monotónico → pared).
- **Ojo con la deuda registrada:** `refreshReady()` escribe estado fuera del
  `Mutex`; si U1.1 lo vuelve a usar, evaluar moverlo bajo `lifecycleMutex`.
- **Verificación:** este repo no tiene emulador; la UI se valida compilando
  (`assembleDebug`, `assembleDebugAndroidTest`) y con la lista de verificación
  manual de este documento.
- **Nota de harness:** `doc-writer` está configurado con un modelo inexistente
  (`opencode-go/longcat-2.5-preview-free`), y el subagente `tester` devuelve
  respuestas vacías con prompts largos (funciona con prompts acotados).
