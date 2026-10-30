# Sesión 2026-10-30 — U2: Historial, detalle y GPX

- **Fecha:** 2026-10-30
- **Tipo:** Implementación (slice U2 — historial/detalle de rides + exportación GPX 1.1)
- **Estado al cerrar:** **U2 implementado y cerrado con gate PASS.**
- **Gate:** `test ktlintCheck detekt assembleDebug lintDebug` y
  `assembleDebugAndroidTest`, en verde. **394 tests, 0 fallos, 0 skipped.**
  `tester: PASS` y `security-reviewer: PASS` (0 hallazgos abiertos) sobre el diff
  final.

---

## Objetivo

Cerrar el ciclo local-first de un ride terminado: ver la lista de rides
guardados con su resumen semanal, abrir el detalle de uno con su traza y sus 8
métricas, y exportarlo a GPX 1.1 con `TrackPointExtension` a un destino elegido
por el usuario vía Storage Access Framework.

El slice también cierra la laguna **§5.5** del handoff: el paso del reloj
monotónico de los puntos al reloj de pared para el `<time>` de GPX.

---

## Qué se implementó, por capa

### Dominio

- **`domain/history/RideRecord.kt`** — read model que consume la UI:
  `id`, `startTimeMillis`, `endTimeMillis`, `distanceM`, `movingTimeSeconds`,
  `stoppedTimeSeconds`, `elevationGainM`, `elevationLossM`, `avgSpeedMps`,
  `maxSpeedMps` y `calories: CalorieSummary?`. La UI nunca ve entidades de Room.
- **`domain/history/RideDetail.kt`** — `RideRecord` + todos los `TrackPoint`
  persistidos, en el orden estable del DAO (timestamp monotónico ascendente).
- **`domain/history/RideHistorySource.kt` / `RideDetailSource.kt`** — puertos de
  lectura. El detalle devuelve `null` para un id desconocido **y** para el ride
  activo: una sesión en curso todavía no es historial.
- **`domain/history/WeeklySummary.kt`** — `WeeklySummaryCalculator.summarize(...)`
  agrega distancia y cantidad de rides de la semana calendario (lunes 00:00 a
  domingo 23:59:59.999) en la zona inyectada. El instante de referencia también
  se inyecta: el cálculo no lee el reloj.
- **`domain/export/GpxExporter.kt`** — puerto de exportación
  `suspend fun export(destination: String, ride: GpxRide): Result<Unit>`.
- **`domain/export/GpxWriter.kt`** — serializador GPX puro JVM (sin imports de
  Android), determinístico y testeable en JVM. Detalle en la sección siguiente.

### Datos

- **`data/repository/RideRecordMapper.kt`** — mapea `RideEntity` a `RideRecord`.
  La banda calórica solo se proyecta si `caloriesKcal`, `caloriesLowKcal`,
  `caloriesHighKcal` y `calorieModel` están completos, son finitos y
  `low <= high`; si falta cualquier parte, `calories = null`. No se rellenan
  huecos con valores inventados (ADR-001).
- **`data/repository/RideRepository.kt`** — implementa `RideHistorySource` y
  `RideDetailSource`. `observeCompletedRides()` filtra `isActive == false`;
  `getRideDetail()` devuelve `null` para el ride activo y carga los puntos por
  `TrackPointDao` para el resto.
- **`data/export/AndroidGpxExporter.kt`** — implementación Android del puerto.
  Serializa y escribe dentro de `Dispatchers.IO`; solo acepta destinos
  `content://` (SAF), abre con modo `"wt"` para truncar y reemplazar el archivo,
  y no loguea ni el destino ni las coordenadas ni el XML (AGENTS.md §7).
- **Sin migración Room.** El nombre del GPX se deriva de `startTimeMillis`; la
  bici queda para U4.

### Inyección de dependencias

- **`di/HistoryModule.kt`** — expone `RideRepository` como `RideHistorySource` y
  `RideDetailSource`, y `AndroidGpxExporter` como `GpxExporter` en
  `SingletonComponent`.

### UI

- **`ui/history/HistoryScreen.kt`** — lista de rides con estados
  `Loading / Empty / Content / Error`, franja semanal siempre visible con datos
  y filas que abren el detalle. Empty: "Todavía no hay rides guardados.".
- **`ui/history/HistoryViewModel.kt`** — `StateFlow<HistoryUiState>` con
  `stateIn(WhileSubscribed(5_000))`; un fallo de lectura degrada a
  `HistoryUiState.Error("No se pudo cargar el historial.")` sin crashear.
- **`ui/history/HistoryUiState.kt`** — `HistoryUiStateFactory.build(...)`
  (determinístico, recibe el instante de referencia) y estados de la pantalla.
- **`ui/history/RideDetailScreen.kt`** — detalle con traza (`RideTrace`), grid de
  8 métricas, aviso si el ride no tiene puntos, banners de resultado de
  exportación y botón para exportar. El export usa
  `ActivityResultContracts.CreateDocument("application/gpx+xml")` y solo llama
  al ViewModel con URIs `content://`.
- **`ui/history/RideDetailViewModel.kt`** — lee `rideId` desde
  `SavedStateHandle`; mapea `null` a `NotFound` y excepción a `Error`. Exporta
  solo si el estado es `Content` y expone `GpxExportState`
  (`Idle / Exporting / Success / Error`).
- **`ui/history/RideHistoryFormat.kt`** — formato puro: título
  `"30 de octubre de 2026 · 18:42"`, subtítulo `"12,35 km · 45:00"`, franja
  `"Esta semana: 12,35 km en 3 rides"` y nombre por defecto
  `"buzzbike-20261030-1842.gpx"`. Reutiliza `RideFormat` para unidades y
  redondeo.
- **`ui/components/RideMetrics.kt`** — `MetricGrid`, etiquetas y
  `calorieMetricCell` extraídos de `RideScreen`, compartidos por vivo, terminado
  y detalle.
- **`ui/navigation/NavGraph.kt`** — rutas `history` y `history/{rideId}`
  (`NavType.StringType`, id con `Uri.encode`).
- **`ui/ride/RideScreen.kt`** — botón **"HISTORIAL"** en `Ready`; se retiró el
  `MetricGrid` local en favor del compartido.

### Tests

- **59 tests nuevos** en `domain/export/GpxWriterTest`,
  `domain/history/WeeklySummaryTest`, `data/export/AndroidGpxExporterTest`,
  `data/export/HistoryExportIntegrationTest`,
  `data/repository/RideRecordMapperTest`, `RideRepositoryDetailTest`,
  `RideRepositoryHistoryTest`, `ui/history/HistoryUiStateFactoryTest`,
  `HistoryViewModelTest`, `RideDetailViewModelTest` y `RideHistoryFormatTest`.

---

## Formato GPX

### Fuentes

- Esquema GPX 1.1: <https://www.topografix.com/GPX/1/1/gpx.xsd>.
- Extensión Garmin TrackPointExtension v2:
  <https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd>.

### Estructura emitida

- Declaración XML UTF-8 y raíz `<gpx version="1.1" creator="BuzzBike"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2">`.
- `<metadata>` con `<name>` y `<time>` de inicio.
- `<trk>` con `<name>` y `<type>cycling</type>`.
- Cada `<trkpt>` lleva `lat`/`lon` con 7 decimales, luego **`<ele>` →
  `<time>` → `<extensions>`** en ese orden. `<ele>` solo si la altitud es
  finita; `<time>` siempre.
- `<extensions>` se omite si el punto no tiene velocidad ni rumbo; si existe,
  contiene `<gpxtpx:TrackPointExtension>` con `<gpxtpx:speed>` en **m/s** y/o
  `<gpxtpx:course>` en **grados [0,360)**.

### Decisiones de formato

- **Reloj de pared.** `time = startTimeMillis + (pointNanos −
  firstValidNanos) / 1_000_000`, formateado ISO-8601 UTC con milisegundos
  (`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`). Cierra §5.5.
- **Cortes de segmento.** Un salto mayor a `RideParameters.GAP_CUT_SECONDS`
  (60 s) entre puntos consecutivos abre un nuevo `<trkseg>`. Exactamente 60 s
  **no** corta; 60,001 s sí.
- **Rumbo normalizado** a `[0,360)` antes de redondear, de modo que 359,998°
  queda `0.00` y nunca `360.00`.
- **Puntos inválidos.** Se descartan coordenadas NaN/infinitas o fuera de rango
  (`lat` en `[-90, 90]`, `lon` en `[-180, 180)`: +180° es inválido en
  `longitudeType`). El writer no reordena puntos.
- **Ride vacío.** Emite metadata y un `<trk>` sin `<trkseg>`; no lanza.
- **Determinismo.** `Locale.ROOT`, saltos `\n`, indentación de dos espacios y
  orden estable de extensiones.
- **Escape XML por code points.** Se escapan `& < > " '` y se descartan los
  code points fuera de la producción `Char` de XML 1.0 (incluidos surrogates
  sueltos); los pares de surrogates válidos se emiten como un code point.

---

## Decisiones y desviaciones respecto del handoff §5

- **Sin migración Room.** El nombre del GPX y el título del detalle se derivan de
  `startTimeMillis`. `rides` no gana columnas en este slice; la bici asociada
  queda para U4.
- **Sin barra inferior de 3 pestañas.** El historial se abre con un botón
  **"HISTORIAL"** desde `Ready`, en la misma pantalla de ride.
- **Sin MapLibre.** La traza del detalle usa el `RideTrace` ya existente
  (polyline Compose), no un mapa. MapLibre sigue siendo ADR-002 para el futuro.
- **Export por SAF.** `CreateDocument("application/gpx+xml")`; el exporter
  rechaza cualquier esquema distinto de `content://` antes de tocar el
  `ContentResolver`. No se usa `FileProvider` ni rutas de archivo directas.
- **`hasProfile = false` en el detalle.** No hay perfil corporal persistido
  todavía; la celda calórica reporta la banda sin perfil. Debe pasar al valor
  persistido en U3.
- **Ride activo excluido.** La lista filtra `isActive` y el detalle lo mapea a
  `NotFound`; el historial solo muestra rides terminados.
- **Calorías sin banda válida = sin número.** Si la banda persistida está
  incompleta o inconsistente, la celda cae a `RideFormat.UNKNOWN`; no se
  fabrica un estimado.
- **Laguna §5.4 sigue abierta.** El reproceso a 1 Hz no se midió en dispositivo
  real; no es parte de U2.

---

## Verificación

```bash
./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain
./gradlew assembleDebugAndroidTest --console=plain
```

Resultado: **verde**. `test` = **394 tests, 0 fallos, 0 skipped**;
`ktlintCheck`, `detekt`, `assembleDebug` y `lintDebug` en 0;
`assembleDebugAndroidTest` compila.

`tester: PASS` y `security-reviewer: PASS` (0 hallazgos abiertos) sobre el diff
final.

---

## Checklist de verificación manual en dispositivo

La máquina de desarrollo no tiene emulador ni dispositivo conectado, así que U2
queda verificado por tests JVM y build. Falta la pasada manual en hardware real:

- [ ] **Lista:** con rides terminados, `HISTORIAL` muestra filas con fecha/hora,
      distancia y tiempo en movimiento; tocar una abre su detalle.
- [ ] **Empty:** sin rides guardados se muestra la franja semanal y
      "Todavía no hay rides guardados.".
- [ ] **Franja semanal:** el texto coincide con los rides de la semana actual
      (lunes a domingo, zona del dispositivo) y cuenta bien singular/plural.
- [ ] **Detalle con 8 métricas:** distancia, en movimiento, detenido,
      desnivel +, desnivel −, velocidad media, velocidad máxima y calorías con
      banda + modelo.
- [ ] **Exportar y abrir en otra app:** crear el `.gpx` desde el selector SAF y
      abrirlo en una app de mapas/GPX; la traza y los tiempos se ven correctos.
- [ ] **Cancelar exportación:** cancelar el selector SAF no muestra éxito ni
      error y deja reintentar.
- [ ] **Rotación:** rotar en lista y detalle conserva el estado (no recarga el
      ride ni pierde el resultado visible de exportación de forma abrupta).
- [ ] **Ride largo:** un ride de varias horas mantiene la lista fluida y el
      detalle abre sin bloquear el main thread.
- [ ] **Back:** desde detalle vuelve a la lista; desde la lista vuelve a `Ready`.
- [ ] **Ride activo fuera del historial:** con un ride en curso, no aparece en
      la lista y navegar a su id da "no encontrado" en lugar de mostrarlo.

---

## Deuda y riesgos conocidos (baja)

- **`hasProfile = false` fijo en el detalle** hasta que U3 persista el perfil
  corporal.
- **`runCatching` captura `CancellationException`** en el exporter y en
  `RideDetailViewModel.load()`; conviene reevaluar el patrón si se agrega
  cancelación explícita.
- **`HistoryViewModel` usa `System.currentTimeMillis()` no inyectable.** El
  borde de semana (domingo 23:59 → lunes 00:00) puede volver flaky un test que
  dependa del reloj real.
- **`WeeklySummaryTest` sin caso `+Infinity`.** El calculador filtra
  `isFinite()`, pero falta el test explícito.
- **Deuda heredada vigente:** `refreshReady()` fuera del `lifecycleMutex`,
  `stopTracking()` sin `join()` del `trackingJob`, `stopRide` del ViewModel que
  puede salir temprano si el `StateFlow` no propagó, y tests manager-level
  faltantes de parada <10 s / excepción en stop.
- **Sin verificación manual en dispositivo**; el checklist anterior queda
  pendiente.

---

## Handoff a la sesión siguiente

- **Próxima tarea:** **U3 — Ajustes y perfil** (DataStore + perfil corporal).
  Es un slice **Heavy**: agrega una dependencia nueva (DataStore) y persiste
  datos de perfil.
- **Puntos de enganche:** reemplazar `hasProfile = false` en
  `RideDetailScreen.detailMetricCells(...)` por el valor persistido; revisar si
  `RideDetail`/`RideRecord` necesitan el flag o si el ViewModel lo inyecta.
- **Riesgos a considerar:** inyectar el reloj en `HistoryViewModel` para
  eliminar el flaky de borde de semana; evaluar `runCatching` +
  `CancellationException`; mantener la banda calórica sin números inventados.
- **Verificación:** el repo no tiene emulador; correr el gate completo y
  `assembleDebugAndroidTest`, y sumar la verificación manual de U2 cuando haya
  hardware.
