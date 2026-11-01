# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Tarea anterior (U1.1 "permiso de ubicación en runtime"): **completada** y archivada
> en `state/archive/loop-state-2026-10-30-u1.1-permiso-ubicacion.md`.
> Estado de producto vigente: `AGENTS.md` §0.

---

## Tarea — Fase 0: saneamiento del proyecto y cierre del pase de seguridad

### Estado
**completada** (`iteracion: 8` de `max_iteraciones: 8`) · clase **Heavy**.
Cerrada con `tester: PASS` (415 tests) y `security-reviewer: PASS` (0 hallazgos
altos/críticos; 2 LOW residuales). Sin escalado.

### Criterio de aceptación (cumplido)
1. Docs alineados con el estado real (conteos, toolchain, CI, emulador).
2. Hallazgos del pase de seguridad cerrados por causa raíz, sin `@Suppress`
   ni thresholds relajados.
3. Gate formal completo en verde + instrumentados compilando.
4. Conteo de tests y claims de entorno verificados contra ejecución real.

### Iteraciones
| Iter | Slice | Resultado | Notas |
|---|---|---|---|
| 0 | plan + macros | PASS | macroplan Fases 0–6 en `docs/roadmap.md` §0 |
| 1 | CI/release + SQLCipher + AAD | **PASS** (commit `9bf3aa1`) | ALTO: se publicaba APK debug; SQLCipher 4.9.0 → 4.19.1 |
| 2 | keystore.properties + verificaciones de release | PASS | `printf` + escape de backslashes; `aapt`/`apksigner` |
| 3 | detekt `ThrowsCount` + re-sellado fail-safe | PASS | sin `@Suppress`; `fill(0)`; fail-closed solo en unseal original |
| 4 | `load()` idempotente + `disconnect()` atómico | PASS | `loaded`+`generation`; `NonCancellable`; orden secret→config→outbox→scheduler |
| 5 | `SyncMutationGate` + epoch de revocación | PASS (código) | cierra ALTO de revocación por instancia |
| 6 | refactor detekt (`SyncGatekeeper`, `SyncSettingsOperations`) | **FAIL detekt → PASS** | `LongParameterList` + `LabeledExpression`, corregidos sin suprimir |
| 7 | cobertura exigida por tester + fix `testConnection` | **FAIL → PASS** | 2 tests faltantes (2 VMs, coordinador vs revoke) + resultado obsoleto por generación |
| 8 | verificación final + docs | **PASS** | 415 tests, detekt 0, instrumentados compilan |

### Verificación final
- `./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain` → **verde**.
- `./gradlew assembleDebugAndroidTest --console=plain` → **compila**.
- `test` = **415 tests, 0 fallos, 0 errores, 0 skipped** (46 suites); `detekt` 0.
- Mutaciones de no-vacuidad ejecutadas y restauradas sobre los 3 tests nuevos.
- `security-reviewer`: **PASS** — ALTO de revocación cerrado; sin bypass por el
  seam `SyncConsentGate` (binding Hilt = `SyncGatekeeper`).
- Afirmación "sin emulador" corregida en README/AGENTS/testing/setup-entorno:
  AVD **`tester36`** existe y `connectedDebugAndroidTest` corrió **6/6 PASS**.

### Deuda abierta (baja, no bloqueante)
- Ventana transitoria entre el último chequeo de epoch y `scheduleAfterSave` en
  `SyncSaveRunner.commit` (neutralizada por el cleanup de `disconnect()`).
- Entrega del worker no toma el gate: riesgo de una entrega en vuelo tras
  revocar, ya aceptado en ADR-003 ("Ventana de desconexión").
- Autofill opt-out de Compose (limitación de plataforma, documentada).
- Regex `CN=Android Debug` sin anclar (fail-closed igual).

### Archivos principales
- Seguridad: `sync/AndroidKeystoreSecretSealer.kt`,
  `data/local/DatabasePassphraseProvider.kt`, `domain/sync/{SecretStorage,
  SyncMutationGate,SyncGatekeeper,RideSyncCoordinator}.kt`,
  `sync/{SharedPrefsSecretStorage,StoredHmacKeyProvider}.kt`,
  `ui/settings/{SyncSettingsViewModel,SyncSettingsOperations,SyncSettingsScreen}.kt`,
  `di/SyncModule.kt`.
- CI: `.github/workflows/android.yml`, `app/build.gradle.kts`,
  `app/gradle.lockfile`, `gradle/libs.versions.toml`.
- Docs: `AGENTS.md`, `README.md`, `CHANGELOG.md`, `docs/roadmap.md`,
  `docs/testing.md`, `docs/setup-entorno.md`.

---

## Tarea — Fase 3: U4 Garaje y desgaste

### Estado
**completada** · clase **Heavy**: `tester: PASS` (552 JVM + 10 instrumentados) y
`security-reviewer: PASS` (3 LOW, defensa en profundidad). 2026-11-01.

### Decisiones
- Fórmula tal cual ADR-004:29-38 (`max` de ejes normalizados), umbrales 0.80/1.00,
  estados `OK`/`APPROACHING`/`DUE`.
- `lifecycleDays` **nullable** (el ADR no fijaba default → no se inventó).
- Frenos **sin default**: requieren configurar km; el desnivel negativo queda como
  TODO informativo (ADR-004:61-63).
- Defaults: cadena 2000 km, suspensión 100 h, neumáticos 3000 km (mínimo del rango).
- Acumulación **solo** con rides asignados (`rides.bike_id`); base = último
  `SERVICE` o `installedAtMillis`; reloj inyectable.
- **Borrar bici conserva `bike_id`** en los rides (referencia histórica suave):
  el detalle muestra «Bici eliminada», componentes/eventos caen por CASCADE y la
  bici borrada no acumula desgaste. (Decisión tomada al resolver el FAIL del tester.)
- DB v6 sin FK dura en `rides.bike_id` (SQLite no permite añadir FK con ALTER).

### Iteraciones
| Iter | Slice | Resultado |
|---|---|---|
| 1 | S1 dominio `domain/wear` | PASS (487 tests) |
| 2 | S2 Room 5→6 + repos | FAIL (migraciones/ktlint/detekt) → PASS (501) |
| 3 | S3 UI garaje | FAIL (ktlint/detekt TooManyFunctions/ReturnCount) → PASS (529) |
| 4 | S4 bici en el ride | incompleto ×2 (step limit) → PASS con tests (551) |
| 5 | gates: tester **FAIL** (producto: borrado) + probe temporal | fix: conservar `bike_id`; probe eliminado |
| 6 | cierre: KDoc, test permanente, docs | PASS (552) |

### Verificación
- `test` = **552**, 0 fallos; `detekt` 0; `connectedDebugAndroidTest` **10/10**.
- Migración 5→6 validada en emulador; desgaste 100 km → 5%, 1600 km → 80%, 2000 km → 100%.

### Deuda
- Sin UI para editar el ciclo de vida de un componente ni historial de servicios
  (el repo lo soporta; falta pantalla).
- Caps de longitud solo en UI (`notes` sin cota) — defensa en profundidad.
- N+1 en `assessWear` por bici (no medido; sin bloqueo).

---

## Tarea — Fase 2: U3 Ajustes y perfil

### Estado
**completada** · clase **Heavy**: `tester: PASS` (465 JVM + 9 instrumentados) y
`security-reviewer: PASS` (0 hallazgos). 2026-11-01.

### Decisiones
- Perfil en **Room cifrado** (`user_profile`, fila única), **no DataStore** →
  ADR-007 nuevo (dato personal, frontera de cifrado, nunca sale del dispositivo).
- `hasProfile` = perfil **completo** (peso+altura+edad+sexo>0), según ADR-001:225;
  antes era solo peso.
- **Mifflin-St Jeor** implementado y usado como baseline de paradas
  (`REE/1440`) con perfil completo; `MetTable.STOP_MET` como fallback.
- Snapshot del perfil por ride (`profile_*`), fijado al iniciar el ride y
  **reutilizado en la recuperación** (nunca el perfil vivo).
- Sin peso: se mantiene el supuesto de 70 kg pero **etiquetado** `supuesto: 70 kg`
  (ADR-001:205).

### Iteraciones
| Iter | Slice | Resultado |
|---|---|---|
| 1 | S1 dominio (isComplete, Mifflin, baseline) | PASS (427 tests) |
| 2 | S2 Room v4→v5 (`user_profile` + `profile_*`) | PASS (439 tests) |
| 3 | S3 UI perfil (form + validación + borrado) | PASS (449 tests) |
| 4 | S4 cableado (ride/detalle/snapshot/`supuesto`) | **FAIL build** → PASS |
| 5 | S5 docs + ADR-007 | PASS |
| 6 | gates: security **FAIL (1 ALTA)** → fix → PASS | recuperación con perfil vivo, `clear()` vs `save()`, `data_extraction_rules` |
| 7 | instrumentados: `AbstractMethodError` de kotlinx-serialization | fix: BOM 1.7.3 → 1.8.1; **9/9 PASS** |

### Verificación
- `test` = **465**, 0 fallos; `detekt` 0; `connectedDebugAndroidTest` **9/9**.
- Migración 4→5 validada con `MigrationTestHelper` en emulador.
- Egress: ningún valor del perfil entra al payload de sync (verificado por security).

### Deuda
- `isComplete` no acota superiores (solo la UI); defensa en profundidad pendiente.
- 23 warnings de `lintDebug` preexistentes.

---

## Tarea — Fase 1: verificación en hardware (emulador)

### Estado
**completada** · clase **Standard** · 2026-10-31.

### Criterio de aceptación (cumplido)
1. `connectedDebugAndroidTest` ejecutado sobre emulador/AVD, no solo compilado.
2. Smoke E2E manual del ciclo de un ride en la app instalada.

### Resultado
- AVD **`tester36`** (API 36, `android-36;google_apis;x86_64`) levantado headless.
- `connectedDebugAndroidTest` = **8/8 PASS**.
- Smoke: permiso de ubicación → Ready → ride → pausa → reanudar → terminar →
  historial → detalle → ajustes de sync → segundo ride. **0 `FATAL EXCEPTION`**.
- Banda calórica siempre `≈ min–max kcal` con modelo (ADR-001 respetado).
- Evidencia local: `build/smoke/01..14.png` (no versionada).
- No automatizado: picker SAF de "EXPORTAR GPX" (UI de sistema).

### Deuda
- Emulador en CI (Fase 1 lo dejó opcional): no implementado, solo local.
- `EXPORTAR GPX` sigue verificado solo por tests, no E2E en dispositivo.

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
