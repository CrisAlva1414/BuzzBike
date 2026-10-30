# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Tarea anterior (U1.1 "permiso de ubicación en runtime"): **completada** y archivada
> en `state/archive/loop-state-2026-10-30-u1.1-permiso-ubicacion.md`.
> Estado de producto vigente: `AGENTS.md` §0.

---

## Tarea — U2: historial, detalle y export GPX

### Estado
**completada** (`iteracion: 2` de `max_iteraciones: 4`) · clase **Heavy**.
Cerrada el 2026-10-30 con `tester: PASS` y `security-reviewer: PASS`.

### Criterio de aceptación (cumplido)
1. Lista de historial desde `RideDao.getAllRides()` (`Flow`), sin rides activos,
   orden descendente, con estados Loading / Empty / Content / Error.
2. Franja semanal calculada, no persistida, semana lunes 00:00 → lunes siguiente
   exclusivo en la zona local; singular/plural.
3. Detalle con traza (`RideTrace`), fecha y las 8 métricas; banda calórica con
   modelo, nunca un número exacto.
4. GPX 1.1 + `TrackPointExtension` v2 válido, con `trkseg` cortado por gaps
   > 60 s y reloj de pared (`start + (nanos − firstValidNanos)/1e6`), exportado
   por SAF sin permisos de almacenamiento.
5. Gate verde con `tester: PASS` y `security-reviewer: PASS`.

### Iteraciones
| Iter | Fecha | Slice | Resultado | Notas |
|---|---|---|---|---|
| 0 | 2026-10-30 | U2 | plan | clase Heavy; research de XSD GPX 1.1 + TPE v2 + SAF |
| 1 | 2026-10-30 | S1 GPX | **FAIL → PASS** | `tester` encontró `course=360.00` y code points ilegales; corregidos en `GpxWriter` |
| — | 2026-10-30 | S2a/S2b | PASS | dominio/datos de historial + UI/navegación |
| — | 2026-10-30 | S3a/S3b | PASS | detalle + exportador + SAF + hardening `content://` |
| 2 | 2026-10-30 | U2 cleanup | **PASS** | puerto `GpxExporter` movido a `domain/export`; serialización a `Dispatchers.IO`; `Uri.encode` en la ruta |

### Verificación final
- `./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain` → **verde**.
- `./gradlew assembleDebugAndroidTest --console=plain` → **compila**.
- `test` = **394 tests, 0 fallos, 0 skipped** (335 previos + 59 de U2).
- `security-reviewer`: **PASS**, 0 hallazgos abiertos (se cerraron 2 LOW de S1,
  2 LOW de S2a/S2b y 2 LOW de S3a; más 1 LOW de tester).

### Archivos principales
- Dominio: `domain/export/{GpxWriter,GpxExporter}.kt`,
  `domain/history/{RideRecord,WeeklySummary,RideHistorySource,RideDetail,RideDetailSource}.kt`.
- Datos: `data/export/AndroidGpxExporter.kt`,
  `data/repository/{RideRecordMapper,RideRepository}.kt`, `di/HistoryModule.kt`.
- UI: `ui/history/*`, `ui/components/RideMetrics.kt`,
  `ui/navigation/NavGraph.kt`, `ui/ride/RideScreen.kt`.
- Tests nuevos: 59 (GPX, historial, mapper, VM de historial/detalle, exportador
  Robolectric, integración repo→detalle→XML).

### Deuda abierta (baja, no bloqueante)
- `RideDetailScreen` usa `hasProfile = false`: en v1 no existe perfil; cuando U3
  persista `calHasProfile`, el detalle debe leerlo.
- `AndroidGpxExporter` usa `runCatching`, que captura `CancellationException`
  (higiene de corrutinas; no es un problema de seguridad).
- `HistoryViewModel` usa `System.currentTimeMillis()` no inyectable: el test
  semanal puede volverse flaky en el borde domingo/lunes (la calculadora pura sí
  está cubierta con reloj inyectado).
- `WeeklySummaryTest` no cubre `+Infinity` (misma rama que `NaN`).
- `refreshReady()` sigue escribiendo fuera del `lifecycleMutex`.
- `TrackingManager.stopTracking()` no hace `join()`.
- `stopRide` del ViewModel puede salir temprano si el `StateFlow` no propagó.
- TalkBack: posible anuncio duplicado en `MetricCell`/`StatusBanner`.
- Lagunas del handoff §5.4 (reproceso a 1 Hz sin medir en dispositivo).
- Sin dispositivo/emulador: la UI y el SAF real quedan pendientes de la
  verificación manual de `docs/sessions/2026-10-30-u2-historial-detalle-gpx.md`.

### Nota de harness
- `doc-writer` sigue con un modelo inexistente
  (`opencode-go/longcat-2.5-preview-free`): la documentación de cierre se delegó
  a `builder` con alcance solo-docs.
- `builder`/`tester` pueden devolver respuesta vacía con prompts largos; el
  trabajo sí queda hecho. Verificar el árbol (`git status`, tests) después.

---

## Contexto para retomar (leer antes de tocar código)

- **Últimos commits:** pendiente de commit de cierre de U2 (código + docs).
- **Gate actual:** **394 tests**, 0 fallos; `ktlintCheck`, `detekt`,
  `assembleDebug`, `lintDebug` y `assembleDebugAndroidTest` en verde.
- **Contrato de sesión:** `domain/ride/session/RideSessionState.kt`.
- **Historial:** `domain/history/` (modelos y puertos) + `ui/history/`.
- **GPX:** `domain/export/GpxWriter.kt` (serializador puro) +
  `data/export/AndroidGpxExporter.kt` (SAF, `content://`, `"wt"`, IO).
- **Auto-pausa:** `MovingTimeEstimator` — ADR-005, sin umbrales nuevos.
- **Calorías:** siempre banda `≈ min–max kcal` + modelo (ADR-001).
- **Próximo slice:** **U3 — Ajustes y perfil** (DataStore + perfil corporal;
  dependencia nueva → Heavy). Después U4 Garaje, U5 mapa, U6 accesibilidad.

### Deuda heredada (no bloqueante — no la arregles salvo que el slice la toque)
- `TrackingManager.stopTracking()` sin `join()` → posible punto huérfano.
- `refreshReady()` / `acknowledgeFinished()` escriben fuera del `lifecycleMutex`.
- `stopRide` del ViewModel puede salir temprano si el `StateFlow` no propagó.
- Faltan tests manager-level de parada <10 s y de "stop lanza excepción".
- TalkBack: posible anuncio duplicado en `MetricCell`/`StatusBanner`.
- `RideFormat`: casos negativos/NaN, `low == high`, locale hostil.
- Atomicidad real de `@Transaction` en DAOs no ejercitada con Room in-memory.
- U1.1 follow-ups: "Abrir Ajustes" tras denegación permanente; refresh en
  `ON_RESUME`; `ACCESS_BACKGROUND_LOCATION` (requiere ADR).
