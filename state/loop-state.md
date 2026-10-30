# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Tarea anterior (U1.1 "permiso de ubicación en runtime"): **completada** y archivada
> en `state/archive/loop-state-2026-10-30-u1.1-permiso-ubicacion.md`.
> Estado de producto vigente: `AGENTS.md` §0.

---

## Tarea encolada — U2: historial y detalle + GPX

### Estado
**pendiente de plan** (`iteracion: 0` · `max_iteraciones: 4`).
No se arrancó: el próximo paso es leer el handoff §5, armar el plan y
clasificarlo (triage) antes de delegar en `builder`.

### Alcance conocido (handoff §5)
- Lista de rides desde `RideDao.getAllRides()`.
- Franja semanal calculada (no persistida).
- Detalle de ride con traza y las 8 métricas.
- Exportación **GPX 1.1 + `TrackPointExtension`**.
- Aquí se cierra la laguna §5.5: paso de reloj monotónico → reloj de pared para
  los timestamps del GPX.
- Probablemente requiere: decidir formato de exportación y destino (Storage
  Access Framework, sin permiso de almacenamiento), y una columna/derivación de
  nombre del ride (ver deuda).

### Clase de triage (a confirmar al planificar)
Probablemente **Standard**: lectura de DB + UI + escritura de un archivo que el
usuario elige (SAF). Subir a **Heavy** si aparece tocar esquema Room (migración),
permisos, o el formato de datos exportados. Ante la duda, subir de clase.

### Antes de tocar código
- Leer `docs/sessions/2026-10-30-u1-ride-en-vivo.md` (lagunas §5.4/§5.5) y
  `docs/sessions/2026-10-30-u1.1-permiso-ubicacion.md`.
- Confirmar el contrato de `RideDao`/`RideRepository` y si `getAllRides()`
  devuelve lo necesario para la franja semanal y las 8 métricas sin N+1.
- Si el export necesita un `ActivityResultContracts.CreateDocument`, verificar
  que `activity-compose` (ya presente) alcance; no agregar dependencias sin
  justificar.
- GPX: validar contra fixtures (estructura 1.1 + extensión Garmin
  `TrackPointExtension` v2), sin red.

---

## Contexto para retomar (leer antes de tocar código)

- **Últimos commits:** `ca024fd` (`feat(ui): request location permission at
  runtime`) y el commit de cierre de estado/documentación de U1.1.
- **Gate actual:** **335 tests**, 0 fallos; `ktlintCheck`, `detekt`,
  `assembleDebug`, `lintDebug` y `assembleDebugAndroidTest` en verde.
- **Contrato de sesión:** `domain/ride/session/RideSessionState.kt`
  (`Idle / Ready(gps, hasBarometer) / Recording / Paused / Finished / Recoverable`).
- **Permiso de ubicación:** `ui/ride/LocationPermissionUi.kt` (helper puro) +
  `RideViewModel.onLocationPermissionResult()` (no-op fuera de `Ready`).
- **Auto-pausa:** `MovingTimeEstimator` — ADR-005, sin umbrales nuevos.
- **Calorías:** siempre banda `≈ min–max kcal` + modelo (ADR-001).
- **Handoff de diseño:**
  `/home/pc01/Escritorio/buzzbike-handoff/android-handoff/ANDROID_HANDOFF.md`.

### Deuda abierta (baja, no bloqueante — no la arregles salvo que el slice la toque)
- `TrackingManager.stopTracking()` no hace `join()` del `trackingJob` → un
  `saveTrackPoint` en vuelo podría dejar un punto huérfano tras `discard`
  (fix acotado sugerido: `stopTrackingAndJoin()`).
- `refreshReady()` escribe estado fuera del `lifecycleMutex`. El call-site nuevo
  de U1.1 ya tiene guard `is Ready`, pero el método sigue público sin guard
  interno; un guard dentro del método (o moverlo bajo el mutex) eliminaría la
  clase de bug.
- `stopRide` del ViewModel puede salir temprano si el `StateFlow` no propagó →
  FGS vivo hasta el siguiente toque.
- Cobertura: falta test manager-level de parada <10 s y de "stop lanza
  excepción → `service.stop()`"; y no-op de `onLocationPermissionResult()` en
  `Paused`/`Recoverable`/`Finished` (hoy cubierto estructuralmente por `is Ready`).
- TalkBack: posible anuncio duplicado en `MetricCell`/`StatusBanner`.
- `RideFormat`: faltan casos negativos/NaN en distancia, `low == high`, locale
  default hostil.
- Atomicidad real de `@Transaction` en default methods de DAO no ejercitada con
  Room in-memory (sin emulador).
- Lagunas del handoff §5.4 (reproceso a 1 Hz sin medir en dispositivo) y §5.5
  (monotónico → reloj de pared para GPX, **es de U2**).
- U1.1 follow-ups: "Abrir Ajustes" tras denegación permanente; refresh en
  `ON_RESUME`; `ACCESS_BACKGROUND_LOCATION` (requiere ADR).

### Nota de harness
- `doc-writer` está configurado con un modelo inexistente
  (`opencode-go/longcat-2.5-preview-free`) y **no arranca**. La documentación de
  cierre se delegó a `builder` con alcance solo-docs. Arreglar la config del
  subagente antes de la próxima sesión que dependa de él.

## Iteraciones

| Iter | Fecha | Slice | Resultado | Notas |
|---|---|---|---|---|
| — | — | — | pendiente | U2 arranca en 0 |
