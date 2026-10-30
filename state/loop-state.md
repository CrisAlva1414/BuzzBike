# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Tarea anterior (U1 "Ride en vivo"): **completada** y archivada en
> `state/archive/loop-state-2026-10-30-u1-ride-en-vivo.md`.
> Estado de producto vigente: `AGENTS.md` §0.

---

## Tarea encolada — U1.1: permiso de ubicación en runtime

### Estado
**pendiente de ejecución** (plan **aprobado por la persona el 2026-10-30**).
`iteracion: 0` · `max_iteraciones: 4`

### Por qué existe
U1 cerró con la pantalla completa y el estado sellado, pero **nadie pide el
permiso en runtime**: en una instalación nueva `RideSessionManager.startRide()`
siempre devuelve `null` y deja `Ready(NO_PERMISSION)`, con el botón INICIAR RIDE
deshabilitado. La app puede grabar, pero no puede arrancar un ride. El plan se
dejó sin implementar a propósito porque es superficie de permisos (Heavy) y
requería aprobación humana; ya está aprobada.

### Clase de triage
**Heavy** — toca permisos de ubicación. La aprobación humana del plan **ya está
dada** (no hace falta volver a pedirla), pero el gate es más estricto:
`tester` + `security-reviewer` obligatorios, y cualquier necesidad de tocar
`AndroidManifest.xml` **detiene la ejecución** (hoy no hace falta: los permisos
ya están declarados).

### Plan aprobado
1. En `Ready(NO_PERMISSION)` la UI ofrece una acción primaria **"DAR PERMISO"**
   que dispara el diálogo del sistema con
   `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`
   para `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`. **Sin tocar el
   manifest** (ambos ya declarados).
2. Al volver del diálogo (concedido o no), re-evaluar el estado de sesión:
   `RideSessionManager.refreshReady()` (ya existe) → `Ready(SEARCHING)` cuando
   hay permiso fino, o `Ready(NO_PERMISSION)` cuando no. Un nuevo `fun
   onLocationPermissionResult()` en `RideViewModel` que delegue es suficiente.
3. Concedido: el botón INICIAR RIDE queda habilitado y el flujo sigue igual
   (el guard del manager ya impide crear fila sin permiso).
4. Denegado: se mantiene `NO_PERMISSION` con copy claro ("Sin permiso de
   ubicación no se puede grabar un ride"). No reintentar automáticamente ni
   pedir dos veces seguidas.
5. **Opcional (solo si sale limpio)**: si tras una denegación
   `shouldShowRequestPermissionRationale` es `false`, ofrecer "ABRIR AJUSTES"
   con `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`. Si agrega
   complejidad o riesgo, se deja como follow-up y se anota.
6. La lógica de decisión (qué acción/label mostrar según
   `GpsStatus` + resultado del pedido) va en un helper **puro** en `ui/ride/`
   (p. ej. `LocationPermissionUi.kt`), testeable en JVM. La pantalla solo lo
   consume.

### Criterio de aceptación
- [ ] `Ready(NO_PERMISSION)` muestra "DAR PERMISO" y al conceder pasa a
      `Ready(SEARCHING)` con INICIAR RIDE habilitado, **sin reiniciar la app**.
- [ ] Denegar deja `NO_PERMISSION` con copy claro y sin crash.
- [ ] Ningún camino arranca tracking ni crea fila sin permiso (regresión de U1).
- [ ] Manifest intacto; sin permisos nuevos; sin dependencias nuevas.
- [ ] Helper de decisión con tests JVM (concedido / denegado / estado inicial).
- [ ] Gate verde: `./gradlew test ktlintCheck detekt assembleDebug lintDebug`
      y `./gradlew assembleDebugAndroidTest`.
- [ ] `tester: PASS` + `security-reviewer: PASS` explícitos.

### Archivos esperados
- `app/src/main/java/com/buzzbike/app/ui/ride/RideScreen.kt` (solo el bloque `Ready`)
- `app/src/main/java/com/buzzbike/app/ui/ride/RideViewModel.kt` (delegación)
- NUEVO `app/src/main/java/com/buzzbike/app/ui/ride/LocationPermissionUi.kt` + test
- `RideSessionManager.refreshReady()` ya existe: **no** cambiar el dominio salvo
  que falte algo y se reporte.

### Fuera de alcance
- Pedido de permiso en background / `ACCESS_BACKGROUND_LOCATION` (no está en v1
  y requiere ADR + revisión de Play; la app es local-first).
- Notificaciones (`POST_NOTIFICATIONS`) y FGS tipo `health`: siguen diferidos.
- Deep link a ajustes del sistema si complica el slice (anotar como follow-up).

### Verificación manual (no hay emulador en esta máquina)
- Instalación limpia → INICIAR RIDE deshabilitado + "DAR PERMISO".
- Conceder → habilita sin reiniciar. Denegar → mensaje, sin crash.
- Revocar permiso desde Ajustes con la app en pausa → reanudar queda en
  `Paused` (comportamiento ya cubierto por U1, verificar que no se rompió).

---

## Contexto para retomar (leer antes de tocar código)

- **Últimos commits:** `bcf20ec` (cierre de estado de U1) sobre `fcea1ab`
  (`feat(ui): add live ride screen with sealed session state`). Árbol limpio.
- **Gate actual:** 322 tests, 0 fallos; `ktlintCheck`, `detekt`,
  `assembleDebug`, `lintDebug` y `assembleDebugAndroidTest` en verde.
- **Contrato de sesión:** `domain/ride/session/RideSessionState.kt`
  (`Idle / Ready(gps, hasBarometer) / Recording / Paused / Finished / Recoverable`).
- **Auto-pausa:** `MovingTimeEstimator` (`MovingState.isMoving`,
  `stoppedSinceNSanos`) — ADR-005, sin umbrales nuevos.
- **Calorías:** siempre banda `≈ min–max kcal` + modelo (ADR-001).
- **Docs de la sesión anterior:** `docs/sessions/2026-10-30-u1-ride-en-vivo.md`.
- **Handoff de diseño:** `/home/pc01/Escritorio/buzzbike-handoff/android-handoff/ANDROID_HANDOFF.md`.

### Deuda abierta (baja, no bloqueante — no la arregles salvo que el slice la toque)
- `TrackingManager.stopTracking()` no hace `join()` del `trackingJob` → un
  `saveTrackPoint` en vuelo podría dejar un punto huérfano tras `discard`
  (fix acotado sugerido: `stopTrackingAndJoin()`).
- `refreshReady()` / `acknowledgeFinished()` escriben estado fuera del
  `lifecycleMutex`. **Ojo: U1.1 usa `refreshReady()`**, así que si tocás esa
  ruta, considerá moverla bajo el mutex (haría `refreshReady()` suspend).
- `stopRide` del ViewModel puede salir temprano si el `StateFlow` no propagó →
  FGS vivo hasta el siguiente toque.
- Cobertura: falta test manager-level de parada <10 s y de "stop lanza
  excepción → `service.stop()`".
- TalkBack: posible anuncio duplicado en `MetricCell`/`StatusBanner`
  (contentDescription en contenedor sin `clearAndSetSemantics`).
- `RideFormat`: faltan casos negativos/NaN en distancia, `low == high`, locale
  default hostil.
- Atomicidad real de `@Transaction` en default methods de DAO no ejercitada con
  Room in-memory (sin emulador).
- Lagunas del handoff §5.4 (reproceso a 1 Hz sin medir en dispositivo) y §5.5
  (monotónico → reloj de pared para GPX, es de U2).

### Después de U1.1
**U2 — Historial y detalle + GPX** (handoff §5): lista desde
`RideDao.getAllRides()`, franja semanal calculada, detalle con traza, 8 métricas,
exportar GPX 1.1 + `TrackPointExtension` (aquí se cierra la laguna §5.5).

## Iteraciones
| Iter | Fecha | Slice | Resultado | Notas |
|---|---|---|---|---|
| — | — | — | pendiente | arranca en 0 |
