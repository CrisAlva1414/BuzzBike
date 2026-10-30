# Loop State (archivado) — U1.1: permiso de ubicación en runtime

> Archivado el 2026-10-30 al cerrar el slice. Bitácora vigente: `state/loop-state.md`.
> Estado de producto: `AGENTS.md` §0. Bitácora de sesión:
> `docs/sessions/2026-10-30-u1.1-permiso-ubicacion.md`.

## Resultado

**Cerrado** con `tester: PASS` + `security-reviewer: PASS` (0 hallazgos tras una
iteración de corrección). Commit de código: `ca024fd`
(`feat(ui): request location permission at runtime`). Gate: **335 tests**,
`ktlintCheck`, `detekt`, `assembleDebug`, `lintDebug` y `assembleDebugAndroidTest`
en verde. Manifest, dependencias y lockfiles intactos (verificado por el
orchestrator con `git diff --stat`).

`iteracion: 1` · `max_iteraciones: 4`

### Por qué existía
U1 cerró con la pantalla completa y el estado sellado, pero nadie pedía el
permiso en runtime: en una instalación nueva `RideSessionManager.startRide()`
siempre devolvía `null` y dejaba `Ready(NO_PERMISSION)` con INICIAR RIDE
deshabilitado. El plan se dejó sin implementar a propósito porque es superficie
de permisos (Heavy) y requería aprobación humana; se aprobó el 2026-10-30.

### Clase de triage
**Heavy** — permisos de ubicación. Gate estricto: `tester` + `security-reviewer`.
No hizo falta tocar `AndroidManifest.xml`.

### Plan aprobado (ejecutado)
1. En `Ready(NO_PERMISSION)`, acción primaria **"DAR PERMISO"** con
   `rememberLauncherForActivityResult(RequestMultiplePermissions())` para
   `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`. Sin tocar el manifest.
2. Al volver del diálogo, `RideViewModel.onLocationPermissionResult()` →
   `RideSessionManager.refreshReady()`.
3. Concedido (fino): INICIAR RIDE habilitado y flujo normal; el guard del
   manager sigue impidiendo crear fila sin permiso.
4. Denegado: `NO_PERMISSION` con copy claro, sin reintento automático.
5. "ABRIR AJUSTES" quedó como follow-up (no implementado).
6. Lógica de decisión en helper puro `ui/ride/LocationPermissionUi.kt`.

### Criterio de aceptación (verificado)
- [x] `Ready(NO_PERMISSION)` muestra "DAR PERMISO" y al conceder pasa a
      `Ready(SEARCHING)` con INICIAR RIDE habilitado, sin reiniciar la app.
- [x] Denegar deja `NO_PERMISSION` con copy claro y sin crash.
- [x] Ningún camino arranca tracking ni crea fila sin permiso (regresión de U1
      cubierta por tests manager-level y de ViewModel).
- [x] Manifest intacto; sin permisos nuevos; sin dependencias nuevas.
- [x] Helper de decisión con tests JVM (inicial / concedido / denegado /
      aproximado / defensivo).
- [x] Gate verde.
- [x] `tester: PASS` + `security-reviewer: PASS` explícitos.

### Archivos finales
- `app/src/main/java/com/buzzbike/app/ui/ride/RideScreen.kt` (launcher + bloque `Ready`)
- `app/src/main/java/com/buzzbike/app/ui/ride/RideViewModel.kt` (`onLocationPermissionResult` con guard)
- NUEVO `app/src/main/java/com/buzzbike/app/ui/ride/LocationPermissionUi.kt`
- NUEVO `app/src/test/java/com/buzzbike/app/ui/ride/LocationPermissionUiTest.kt`
- `app/src/test/java/com/buzzbike/app/ui/ride/RideViewModelTest.kt`

### Desvíos respecto del plan
- Se agregó el resultado `APPROXIMATE` (no previsto) para distinguir
  "solo ubicación aproximada" de "denegado": el dominio exige FINE, así que
  marcar GRANTED con coarse producía copy engañoso. `classify(fineGranted,
  coarseGranted)` es puro y testeado.
- Se agregó un guard de estado en `onLocationPermissionResult()` (no-op fuera de
  `Ready`) para que un callback tardío no pise un ride activo.

### Fuera de alcance (follow-ups vivos)
- "Abrir Ajustes" / `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` tras
  denegación permanente.
- Re-evaluar el permiso en `ON_RESUME` (revocación desde Ajustes con la app en
  pausa deja la UI en `NO_PERMISSION` hasta el próximo refresh).
- `ACCESS_BACKGROUND_LOCATION` (requiere ADR + revisión; la app es local-first).
- `POST_NOTIFICATIONS` y FGS tipo `health`: siguen diferidos.

## Iteraciones

| Iter | Fecha | Slice | Resultado | Notas |
|---|---|---|---|---|
| 0 | 2026-10-30 | U1.1 implementación | tester PASS · security PASS | builder: 328 tests. tester re-corrió y agregó un invariante ("gps manda sobre resultado obsoleto"): 329 tests. security: 2 hallazgos LOW (semántica FINE/COARSE; guard de `refreshReady`) + 3 INFO. |
| 1 | 2026-10-30 | U1.1 cierre de hallazgos LOW | tester PASS · security PASS (0 hallazgos) | `classify(...)` + `APPROXIMATE` + guard `is Ready`; +6 tests. Total 335. |
