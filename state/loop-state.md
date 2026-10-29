# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Formato de commits: Conventional Commits (ver AGENTS.md).

---

## Iteration 0 — 2026-10-02 — Discovery / Scoping

### Tarea
Inicio de proyecto greenfield: app Android open source (MIT) de trackeo de rutas
de bicicleta con GPS + sensores inerciales, cálculo de gasto calórico,
tracking de desgaste de componentes, y sync opcional a un agente de IA local
(Hermes Agent de Nous Research) vía webhook.

### Estado
**EN CURSO — scope aprobado por el usuario (2026-10-02).** Slices 1–8 approved.
Gate obligatorio por slice: `tester: PASS` + `security-reviewer: PASS`.

### Criterio de aceptación (a confirmar por el usuario)
- (TODO) Ver `docs/adr/` cuando el usuario defina el scope.

### Entorno (verificado)
| Item | Estado |
|------|--------|
| Repo | `/home/notebook01/Proyectos/BuzzBike` — git inicializado, sin commits |
| SO | Zorin OS 17.3 (Ubuntu base) |
| JDK | **AUSENTE** — `java: command not found`, no hay `/usr/lib/jvm`, no hay sdkman |
| Android SDK | **AUSENTE** — `ANDROID_HOME` y `ANDROID_SDK_ROOT` vacíos |
| adb | presente (`/usr/bin/adb`) |
| Python | `/home/notebook01/anaconda3/bin/python3` |
| Disk libre | 20 GB en `/` (Android SDK + build ≈ 8–12 GB) |

**Bloqueante para cualquier slice de código:** falta JDK 17+ y Android SDK.

### Investigación delegada (3 researcher en paralelo, completada)

#### researcher #1 — Sensores Android + cálculo calórico
- FGS `health|location` es obligatorio para trackeo continuo en A14+.
- Sin `HIGH_SAMPLING_RATE_SENSORS`, Android 12+ capea accel/gyro/mag a **200 Hz**
  (`minDelay` mínimo 5000 µs). Para cadencia/proxy de vibración no hace falta más.
- `FusedLocationProviderClient` con `PRIORITY_HIGH_ACCURACY` basta para v1.
  **Raw GNSS / PPP: NO para MVP** — el propio white paper de Google dice que sin
  correcciones externas el resultado puede ser peor que el del chipset.
- Activity Recognition (`ON_BICYCLE`) sirve como gate de sesión.
- Barómetro = delta de altitud relativo mucho más confiable que altitud GPS
  (que es pésima en vertical). Vertical gain requiere suavizado/histerésis.
- **Calorías — hallazgo central:**
  - Keytel 2005 (pub. validado): `r=0.913` con VO2max, `r=0.857` sin.
    Ecuación pública, reproducible.
  - Watches comerciales vs calorimetría: **error de EE 9–43%** (Wallen 2016, PLOS One).
  - Compendium 2024: MTB 8.5 MET, road 12–13.9 mph 8.0 / 14–15.9 mph 10.0 / >20 mph 16.8.
  - ACSM: `VO2 = 1.8*(W*6.12/masaKg) + 7` — requiere **potenciómetro**.
  - "35 kcal/km" **NO es una constante fisiológica**: es una heurística
    (≈3.5 MET × 70 kg × distancia). Ver objeciones más abajo.
  - MediaPipe Pose en background: NO viable (cámara + FGS `camera` + batería).
  - ANT+: solo en minoría de teléfonos; requiere dongle USB. Descartado del camino crítico.
  - BLE es el estándar: HR `0x180D`, CSC `0x1816`, Power `0x1818`.
- No existe paper público de Meta/Google sobre su algoritmo de calorías.
  **Prohibido inventar la cita.**

#### researcher #2 — Hermes Agent / integración webhook
- **Hermes Agent** (framework, NO el modelo) existe: https://github.com/NousResearch/hermes-agent
  — MIT, Python, con **webhooks HTTP nativos en puerto 8644** y **HMAC V2**
  (`X-Webhook-Signature-V2` = HMAC-SHA256 de `<timestamp>.<body>`, ventana ±300s).
  Tiene idempotencia por delivery ID (cache 1h), rate limit 30 req/min,
  body limit 1 MB, y `deliver_only: true` para notificar sin gastar tokens.
- El MODELO Hermes (Hermes 3 8B / Hermes 4.x) es otra cosa, open-weights en HF.
  Los docswns desalientan usar Hermes 4 dentro del agente (no es tool-calling).
- Patrón recomendado: **Outbox en SQLite + WorkManager → POST firmado**.
- Payload de un ride de 2h en JSON compacto + gzip ≈ 100–250 KB. Cabe en el
  límite de 1 MB sin chunking. Data minimization: mandar métricas + waypoints
  decimados, no el track crudo.
- Aviso dePrivacy: el idempotency cache de Hermes dura 1 h → el receptor debe
  dedupear por `ride.id` además del header.

#### researcher #3 — Mapas, formatos, wear tracking
- **MapLibre Native Android** (BSD) muy activo (v13.5.0, ago 2026). **Usar.**
- **osmdroid está ARCHIVADO** (nov 2024). No usar.
- **OpenFreeMap** — vector tiles, sin API key, sin límites. Requerido:
  atribución visible `© OpenStreetMap contributors` + `© OpenMapTiles`.
- **tile.openstreetmap.org prohíbe offline/prefetch explícitamente** y bloquea
  apps por User-Agent genérico. No usarlo como tile source.
- Google Maps / HERE / MapTiler: incompatibles con una app MIT open source.
- **Offline**: `pmtiles://file://$path` nativo en MapLibre ≥11.7.0.
  `pmtiles://asset://` NO funciona (hay que copiar a filesDir).
- **Formatos**: exportar **GPX 1.1 + TrackPointExtension** (hr/cad/dist).
  **FIT = licencia Garmin propietaria**, no usar como formato nativo.
- **Prior art de wear tracking**: existe `higorslva/biketrackd` (MIT, Android
  Kotlin Compose, MapLibre + OpenFreeMap, wear % + alerts) — casi idéntico al
  proyecto. Verificar antes de reinventar.
- No hay schema estándar de intercambio de componentes entre apps.

### Objeciones a las premisas del usuario (a discutir antes de codear)

1. **"cálculo exacto de calorías"** — no existe con传感oes de teléfono.
   El techo realista sin potenciómetro es ±10–20% (Keytel + MET fusionado).
   Con potenciómetro: ±5–10%. Con calorimetría: es otro proyecto.
   **El objetivo honesto es "el mejor estimador disponible y auditable,
   con la incertidumbre declarada en la UI"**, no "exacto".

2. **"usar la mayoría de sensores"** — más sensores ≠ más precisión.
   Aporta: HR (BLE strap) > potenciómetro > barómetro > GPS速度.
   NO aporta o actively harmful en background: magnetómetro (interferencia),
   Linear acceleration (derivado), Gravity (derivado).
   La IMU del teléfono sirve para *detectar que hay movimiento*, no para medir
   el esfuerzo fisiológico.

3. **"35 kcal/km"** — es una heurística de app genérica, no una constante.
   La dependencia real de la energía en ciclismo es **potencia × tiempo**
   (Watts → joules), no distancia. A 150 W constante vs 250 W constante, la
   distancia cambia pero el gasto calórico es el mismo. Un ride de 20 km en
   plano a 30 km/h y uno de 40 km a 15 km/h con la misma potencia gastan
   **lo mismo**.

4. **"backend local"** — el同意 con que el cálculo重度 (calorías, wear, stats)
   sea 100% on-device es total. Lo que va al agente es un **reporte**, no
   lógica de negocio.

5. **Mapas "freemaps"** — interpreto como mapas libres/open source.
   Confirmar si además quiere modo offline real (PMTiles) o alcanza con online.

### Decisiones de scope — CONFIRMADAS por el usuario (2026-10-02)
- [x] **D1. Sensores: NINGUNO.** El usuario no tiene HR strap ni potenciómetro.
      Pendiente futuro. → El slice 5 arranca en el nivel 3 de la cascada
      (Compendium MET por velocidad GPS). Techo de precisión **±25–35%**.
      La UI debe declarar el margen SIEMPRE, no solo en fallback.
      La arquitectura deja el hook para niveles 1 y 2 sin reescribir `domain`.
- [x] **D2. Hermes Agent corre en el NAS del usuario**, alcanzable desde el
      teléfono por Tailscale (WireGuard). → ADR-003 usa Tailscale como transporte.
- [x] **D3. Mapas: online primero.** MapLibre Native + OpenFreeMap.
      Offline PMTiles diferido (ADR-002). NO construir PMTiles en v1.
- [x] **D4. Distribución: GitHub Releases (APK).** NO Play Store, NO F-Droid en v1.
      Enfoque: cualquiera puede compilar; el APK es cortesía.
- [x] **D5. Toolchain: instalar en esta máquina.** Descarga manual a `~/tools/`
      (no hay sudo; no hay apt). JDK → Temurin 17 x64 desde Adoptium.
- [ ] **D6. Nombre del paquete / app id** — placeholder actual `com.buzzbike.app`.
      **PENDIENTE** — usar `com.buzzbike.app` salvo que el usuario diga otra cosa.

### Dependencia externa notable
Slice 7 (webhook HMAC) depende de que el usuario tenga el Hermes Agent
instalado y accesible por Tailscale. Si no está listo al momento del slice 7,
se implementa contra la spec documentada y se valida con un mock receiver
local. El secret HMAC en Android Keystore es **manejo de secretos** →
aprobación explícita del usuario requerida antes de que `builder` escriba código
(ESQUELADO DE ESCALADO del orchestrator).

### Iteraciones
- Total: 0 (discovery)
---

## Iteration 0 — 2026-10-27 — Revisión de roadmap y plan (clase Heavy)

### Tarea
Revisar el roadmap del proyecto, opinar, y planificar la construcción con foco
explícito en (a) integración con servicios externos y (b) diseño/frontend.

### Estado
**PLANIFICACIÓN — esperando aprobación humana del plan.** No se escribió código.

### Hallazgos de verificación (esta máquina, /home/pc01)
- El roadmap NO existe en este clon: está en el knowledge base (NAS), generado
  en `/home/notebook01`. `docs/` solo tiene ADRs, format y sprint-00.
- Toolchain distinto al de la sesión previa: JDK 21 (`~/.local/jdk-21`),
  `ANDROID_HOME=/home/pc01/android-sdk`, platforms `android-37.0`/`android-37.2`,
  build-tools 36.0.0/37.0.0. **Falta `platforms;android-36`** (compileSdk=36).
- Wrapper pide Gradle 9.8.2; cache solo tiene 9.7.0 → descarga en el primer run.
- Sin `local.properties` (AGP usará `ANDROID_HOME`).
- Remote GitHub ya configurado (`CrisAlva1414/BuzzBike`).

### Bloqueante
El gate del slice 1 (`assembleDebug/test/ktlintCheck/detekt/lint`) **no puede
pasar** hasta resolver el SDK platform 36 o bumpear compileSdk/targetSdk.

### Decisiones pendientes (requieren al humano)
1. `platforms;android-36` vs bump a 37 (cambia targetSdk y superficie de API).
2. Reordenar a **design-first con datos fake** (UI antes que datos/captura).
3. Contradicción ADR-003 vs `network_security_config` (cleartext prohibido vs
   "Tailscale ya cifra") → decidir Tailscale Serve/HTTPS o domain-config.
4. Gate de `security-reviewer`: mantener diferido al release salvo **fase 6
   (secretos+red)**, donde debe ser obligatorio antes de mergear.
5. Aprobación explícita del plan de manejo del secret HMAC (regla de escalado).

### Iteraciones
- Total: 0 (planificación)

---

## Iteration 1 — 2026-10-27 — Gate del scaffold + ADRs del algoritmo

### Tarea
Cerrar el gate de compilación (slice 1) y documentar el algoritmo de traza,
segmentación y energía antes de implementar `domain/`.

### Resultado del gate (verificado por orchestrator y por tester)
| Comando | Exit |
|---|---|
| assembleDebug | 0 |
| test | 0 (5 tests) |
| ktlintCheck | 0 |
| detekt | 0 |
| lint | 0 (0 errores, 6 warnings) |

APK: `app/build/outputs/apk/debug/app-debug.apk` (~60.5 MiB),
`package=com.buzzbike.app`, `minSdk=26`, `targetSdk=36`, `compileSdk=37`,
firma debug v2 válida, `versionCode=1`, `versionName=0.1.0`.

### Cambios de código
- `compileSdk 36 → 37` (AAR metadata de Compose 1.12.x / hilt-navigation 1.4.0
  exige `minCompileSdk=37`; `targetSdk` queda en 36).
- `detekt.yml`: corrección de schema para detekt 1.23.8 (reglas mal ubicadas
  o inexistentes), sin desactivar checks.
- `.editorconfig`: excepción acotada de naming para `@Composable`.
- `Percent.kt`: constantes `PERCENT_MIN/MAX` + contrato `NaN → 0.0`.
- `versionCode=1`, `versionName="0.1.0"`.

### Documentación
- `docs/adr/ADR-005-segmentacion-traza-y-moving-time.md` (nuevo):
  máquina de estados moving/stopped, filtrado, distancia, validación.
- `docs/adr/ADR-006-elevacion-y-desnivel.md` (nuevo): barómetro + DEM + GPS,
  suavizado, histéresis, grade/VAM, guardia de ascensor.
- `docs/adr/ADR-001`: actualización con tabla MET 2024, integración
  sample-a-sample, perfil opcional, paradas y bandas de incertidumbre
  corregidas (±30–40% sin perfil; ±20–30% con perfil; NO intervalos de
  confianza).

### Hallazgos abiertos del tester
- No se pudo probar instalación/arranque en dispositivo (no hay emulador).
- 6 warnings de lint (OldTargetApi, versiones de coroutines, icono, feature GPS).
- Manifest declara permisos para slices futuros (background location, BLE)
  antes de que las features existan: revisar antes del release.

### Próximo paso
Implementar `domain/` en slices: (A) limpieza + segmentación + distancia,
(B) elevación, (C) energía. Tests JVM con trazas sintéticas.

### Iteraciones
- Total: 1 (gate cerrado)

---

## Iteration 2 — 2026-10-27 — Domain slice A: segmentación de traza

### Tarea
Implementar ADR-005: limpieza de traza, máquina moving/stopped, distancia y
agregados de ride, en `domain/` puro (sin Android).

### Resultado del gate
| Comando | Exit |
|---|---|
| test | 0 (39 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

Tester: **PASS**. Security-reviewer: **PASS** (hallazgos medios de robustez,
corregidos los de validación/calidad/overflow).

### Archivos
- `domain/model/TrackPoint.kt`, `RideDescriptors.kt`
- `domain/geo/GeodesicDistance.kt` (puerto + Haversine para tests JVM)
- `domain/ride/RideParameters.kt`, `SpeedResolver.kt`, `TraceCleaner.kt`,
  `MovingTimeEstimator.kt`, `RideAggregator.kt`
- tests: `TraceCleanerTest`, `MovingTimeEstimatorTest`, `RideAggregatorTest`,
  `RideDomainEdgeCaseTest` (12 casos borde del tester)

### Decisiones
- Velocidad **solo Doppler**; sin fallback por distancia/tiempo (el jitter
  urbano lo rompe). Fix sin velocidad = gap/sin evidencia.
- Gaps: 5–60 s cortan moving pero no distancia; >60 s reinician a STOPPED y
  cierran parada/movimiento abiertos.
- maxSpeed = p95 sobre mediana centrada de 5 s, solo samples moving con
  accuracy <=15 m.
- Entradas no finitas/fuera de rango se rechazan o sanean a null.

### Bugs encontrados y corregidos en iteración
- speed infinito contaminaba maxSpeed.
- NaN/Inf/accuracy inválida se filtraban.
- jitter estacionario sin Doppler se auto-reanudaba.
- gap >60 s no reiniciaba estado.
- parada confirmada antes de un gap se perdía.

### Residual (documentado, no bloqueante)
- `movingTimeUncertain`/`STATE_UNCERTAIN` diferido.
- Promedios devuelven 0.0 en vez de null; `InsufficientData` no implementado.
- `maxSpeedConfidence` diferido.
- Adaptador WGS84 real (`Location.distanceBetween`) pendiente para `sensor/`.
- Sin prueba en dispositivo real.

### Próximo paso
Slice B: elevación (ADR-006) — barómetro relativo + ancla DEM/MSL, suavizado,
histéresis, grade/VAM. Después slice C: energía (ADR-001).

### Iteraciones
- Total: 2 (slice A cerrado)

---

## Iteration 3 — 2026-10-27 — Domain slice B: elevación

### Tarea
Implementar ADR-006: fusión barómetro + GPS/DEM, histéresis, guardia de ascensor.

### Resultado
| Comando | Exit |
|---|---|
| test | 0 (44 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Archivos
- `domain/elevation/ElevationModels.kt`, `ElevationParameters.kt`, `BarometricConverter.kt`, `ElevationProcessor.kt`
- `test/domain/elevation/ElevationProcessorTest.kt` (5 tests)

### Decisiones
- Barómetro relativo con fórmula US Standard Atmosphere 1976; p0 no fijo a 1013.25.
- Histéresis 3 m con barómetro, 10 m solo GPS.
- Guardia de ascensor: Δh>3m, Δs<5m, aceleración baja.
- Ancla: GPS MSL > GPS elipsoidal > barómetro relativo.
- Sin interpolación entre gaps.

### Pendiente
- Suavizado Savitzky–Golay completo (mediana 3 + SG 9s/orden 2) — implementación básica.
- De-trending en paradas >30s.
- Grade/VAM sobre ventanas.
- Integración con DEM anchor provider.

### Próximo
Slice C: energía (ADR-001) — MET sample-a-sample, perfil opcional, bandas de incertidumbre.

---

## Iteration 4 — 2026-10-27 — Domain slice C: calorías

### Tarea
Implementar ADR-001: cascada MET sample-a-sample, perfil opcional, incertidumbre.

### Resultado
| Comando | Exit |
|---|---|
| test | 0 (49 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Archivos
- `domain/calories/CalorieModels.kt`, `MetTable.kt`, `CalorieCalculator.kt`
- `test/domain/calories/CalorieCalculatorTest.kt` (5 tests)

### Decisiones
- MET bruto sample-a-sample; paradas a 1.0 MET.
- Sin perfil: 70 kg asumido, incertidumbre ±30–40%.
- Con perfil: ±20–30%.
- Hueco 19–20 mph sin banda → tratado como parada.
- 01050 marcado como ESTIMADO.

### Pendiente
- Suavizado de velocidad 10–30s antes de clasificar banda.
- Keytel/ACSM para sensores futuros.
- UI para mostrar rango/modelo/inputs.

### Próximo
Integración con sensores/UI o tests de integración.

---

## Iteration 5 — 2026-10-27 — Fixes de compilación, ktlint, detekt y seguridad

### Tarea
Corregir errores de compilación, formato ktlint, hallazgos detekt y hallazgo de seguridad #4 (guard `index > 0` en `onGap`).

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (50 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Archivos modificados
- `domain/ride/MovingTimeEstimator.kt` — nullables `accuracyH`, guard `index > 0` en `onGap`, removido veto por acelerómetro (sin campo en TrackPoint)
- `domain/ride/RideAggregator.kt` — `accuracyH` nullable, `isUsableSpeedSample` extraído
- `domain/ride/TraceCleaner.kt` — `isUsableAccuracy` acepta `Double?`, removidas referencias a campos inexistentes
- `domain/ride/engine/RideEngine.kt` — imports, constantes nombradas, uso correcto de `object`s
- `domain/calories/CalorieCalculator.kt` — refactor a helpers, constantes con cita a ADR-001
- `domain/calories/MetTable.kt` — bandas como vals nombradas, constantes de conversión
- `domain/elevation/ElevationProcessor.kt` — helpers top-level, propiedades muertas removidas
- `domain/elevation/BarometricConverter.kt` — constantes, guards combinados
- `domain/elevation/ElevationParameters.kt` — constante `BARO_ALTITUDE_COEFFICIENT_M`
- `data/repository/RideRepository.kt` — campo `bearing` en TrackPointEntity
- `sensor/location/LocationSensor.kt`, `sensor/pressure/PressureSensor.kt`, `sensor/motion/MotionSensor.kt` — detekt fixes
- `ui/ride/RideViewModel.kt`, `ui/ride/RideScreen.kt` — detekt/ktlint fixes
- `test/domain/elevation/ElevationProcessorTest.kt` — formato, dead code removido
- `test/domain/calories/CalorieCalculatorTest.kt` — (sin cambios funcionales)
- `test/domain/ride/TraceCleanerTest.kt` — test nuevo: `accuracyH = null` se rechaza

### Seguridad
- Hallazgo #4 (baja): guard `index > 0` en `MovingTimeEstimator.onGap` — corregido.
- Hallazgos medios #1 (permisos runtime) y #2 (sobre-declaración de permisos) quedan para el slice de integración de sensores.
- Hallazgo #3 (cifrado Room) requiere decisión humana de arquitectura.

### Próximo paso
Integración sensores → dominio → persistencia (TrackingManager), luego UI detallada.

---

## Iteration 6 — 2026-10-27 — Slice 1.1: TrackingManager + permisos (EN CURSO)

### Tarea
Crear TrackingManager que suscriba a LocationSensor, convierta a TrackPoint, persista vía RideRepository, y verifique permisos de ubicación en runtime.

### Estado
- Código implementado por builder, compileDebugKotlin OK, ktlintCheck OK, detekt OK.
- test: 58 tests, 3 fallos en TrackingManagerTest (corrutinas con advanceUntilIdle no avanzan backgroundScope).
- assembleDebug: no ejecutado.
- Decisión de arquitectura pendiente: TrackingManager (domain) importa RideRepository (data), viola AGENTS.md §3. Solución: crear puerto TrackPointWriter en domain, RideRepository lo implementa.

### Próximo paso
Arreglar tests, ejecutar assembleDebug, resolver arquitectura domain→data.

---

## Iteration 6 — 2026-10-27 — Slice 1.1: TrackingManager + permisos (COMPLETADO)

### Tarea
Crear TrackingManager que suscriba a LocationSensor, convierta a TrackPoint, persista vía puerto TrackPointWriter, y verifique permisos de ubicación en runtime.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (58 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK 64 MB) |

### Archivos nuevos
- `domain/ride/session/LocationSensorPort.kt` — interfaz para lecturas de ubicación
- `domain/ride/session/LocationPermissionChecker.kt` — interfaz para verificación de permisos (JVM-testable)
- `domain/ride/session/TrackPointWriter.kt` — puerto de persistencia de track points (AGENTS.md §3)
- `domain/ride/session/TrackingManager.kt` — use case de tracking (start/stop, validación, persistencia)
- `sensor/location/AndroidLocationPermissionChecker.kt` — implementación Android del permiso checker
- `test/domain/ride/session/TrackingManagerTest.kt` — 6 tests con fakes
- `test/sensor/location/AndroidLocationPermissionCheckerTest.kt` — 2 tests del seam puro

### Archivos modificados
- `sensor/location/LocationSensor.kt` — implementa LocationSensorPort, mapea Location → LocationReading puro
- `data/repository/RideRepository.kt` — implementa TrackPointWriter
- `state/loop-state.md` — registro de iteración

### Decisiones de arquitectura
- `TrackingManager` depende de `TrackPointWriter` (domain port), no de `RideRepository` (data) → respeta AGENTS.md §3 (`ui → domain ← data`).
- `LocationPermissionChecker` vive en domain; la implementación Android vive en sensor/.
- `LocationReading` se movió a domain (sin `android.location.Location`) para permitir tests JVM puros.

### Tests
- 58 tests totales, 0 fallos.
- Cubre: sin permiso (startTracking=false), con permiso + persistencia, rideId blank, coordenadas inválidas, doble start, stop tracking.

### Próximo paso
Slice 1.2: agregar PressureSensor y MotionSensor a TrackingManager.

---

## Iteration 7 — 2026-10-27 — Slice 1.2: Pressure + Motion sensors (COMPLETADO)

### Tarea
Integrar PressureSensor y MotionSensor al TrackingManager para capturar lecturas de barómetro y acelerómetro, convertirlas a ElevationSample y MotionReading, y procesarlas con ElevationProcessor.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (61 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK 62 MB) |

### Archivos nuevos
- `domain/elevation/PressureSensorPort.kt` — puerto `pressureUpdates(): Flow<PressureReading>`
- `domain/ride/session/MotionSensorPort.kt` — puerto `motionUpdates(): Flow<MotionReading>`
- `test/domain/ride/session/TrackingManagerElevationTest.kt` — 3 tests JVM

### Archivos modificados
- `sensor/pressure/PressureSensor.kt` — implementa PressureSensorPort
- `sensor/motion/MotionSensor.kt` — implementa MotionSensorPort
- `domain/ride/session/TrackingManager.kt` — nuevos parámetros, `startElevationTracking`, `stopElevationTracking`, `elevationDescriptors` Flow
- `domain/elevation/ElevationProcessor.kt` — `class` → `open class`, `process` → `open fun` (para fake en tests)

### Decisiones
- `ElevationSampleAssembler` combina lecturas de presión y movimiento por timestamp más cercano (ventana 1 s).
- `stopElevationTracking()` agregado para cancelar la suscripción.
- `ElevationProcessor` se abrió para permitir fake en tests (alternativa: usar implementación real).
- `TrackingManager` constructor llega a 7 parámetros; `@Suppress("LongParameterList")` local con justificación en KDoc.
- Los puertos en domain importan `PressureReading`/`MotionReading` desde `sensor.*` (sin imports android.* en domain).

### Próximo paso
Slice 2.1: RideSessionManager (start/stop de ride, estado en tiempo real).

---

## Iteration 8 — 2026-10-27 — Slice 2.1: RideSessionManager (COMPLETADO)

### Tarea
Crear RideSessionManager que coordine el ciclo de vida de un ride: iniciar sesión, recibir puntos del sensor vía TrackingManager, procesar con RideEngine, y exponer estado en tiempo real.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (65 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK generado) |

### Archivos nuevos
- `domain/ride/session/RideSessionWriter.kt` — puerto de dominio (startRide, stopRide, saveRideDescriptors)
- `domain/ride/session/RideSessionManager.kt` — orquestador + RideSessionUiState
- `test/domain/ride/session/RideSessionManagerTest.kt` — 4 tests JVM

### Archivos modificados
- `data/repository/RideRepository.kt` — implementa RideSessionWriter; nuevo saveRideDescriptors
- `domain/ride/engine/RideEngine.kt` — open class / open fun processRide (para fake en tests)
- `domain/ride/session/TrackingManager.kt` — open class, open en miembros (para fake en tests)

### Decisiones
- startRide()/stopRide() son suspend (necesario por RideSessionWriter)
- saveRideDescriptors recibe elevación y calorías como parámetros opcionales
- RideSessionManager recibe CoroutineScope opcional para colectar flows
- RideEngine y TrackingManager se abrieron para permitir fakes (mismo precedente que slice 1.2)

### Próximo paso
Slice 2.2: Persistencia de descriptores finales al detener ride (ya parcialmente cubierto por saveRideDescriptors, pero necesita integración completa con RideViewModel y UI).

---

## Iteration 9 — 2026-10-27 — Slice 2.2: Persistencia de descriptores finales (COMPLETADO)

### Tarea
Asegurar que los descriptores finales de un ride se persistan al detener, y conectar RideViewModel a RideSessionManager.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (68 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK generado) |

### Archivos nuevos
- `di/RideSessionModule.kt` — wiring Hilt para TrackingManager y RideSessionManager
- `test/ui/ride/RideViewModelTest.kt` — 2 tests JVM (sin Robolectric)

### Archivos modificados
- `ui/ride/RideViewModel.kt` — migrado de RideRepository a RideSessionManager; expone StateFlow<RideSessionUiState>
- `data/repository/RideRepository.kt` — agregado getRideWithDescriptors(rideId)
- `test/domain/ride/session/RideSessionManagerTest.kt` — +1 test de persistencia final

### Decisiones
- RideViewModel delega startRide()/stopRide() a RideSessionManager
- getRideWithDescriptors delega en getRideById (RideEntity ya tiene todas las columnas de descriptores)
- RideSessionModule provee TrackingManager con sensores reales y RideSessionManager con RideEngine + RideRepository
- viewModelScope manejado con StandardTestDispatcher() en tests JVM (sin Robolectric)

### Bloques 1 y 2 cerrados
- Bloque 1 (sensores → dominio → persistencia): 1.1 + 1.2 completados
- Bloque 2 (sesión de ride): 2.1 + 2.2 completados
- Total tests: 68, 0 fallos

### Próximo paso opcional
Bloque 3: HermesSender configurable (webhook/REST) + WorkManager + outbox + HMAC (pendiente aprobación humana para secretos).

---

## Iteration 10 — 2026-10-27 — Slice 3.1: HermesSender configurable (COMPLETADO)

### Tarea
Crear interfaz HermesSender configurable con implementaciones WebhookHermesSender y RestHermesSender, más HermesConfig y HermesSenderFactory. Todo en domain/sync, sin red real (stubs).

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (79 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK generado) |

### Archivos nuevos
- `domain/sync/HermesSender.kt` — interfaz + SendResult
- `domain/sync/HermesConfig.kt` — HermesConfig, SenderType, endpointValidationError()
- `domain/sync/WebhookHermesSender.kt` — stub de webhook
- `domain/sync/RestHermesSender.kt` — stub de REST
- `domain/sync/HermesSenderFactory.kt` — factory por SenderType
- `test/domain/sync/HermesSenderFactoryTest.kt` — 3 tests
- `test/domain/sync/WebhookHermesSenderTest.kt` — 4 tests
- `test/domain/sync/RestHermesSenderTest.kt` — 4 tests

### Decisiones
- Validación de endpoint: java.net.URL para parseo + protocolo http/https + host no vacío (sin abrir conexión).
- Mensaje de error genérico para no exponer credenciales potenciales en URLs malformadas.
- rideId y payload no se usan todavía (transporte real en slice 3.2).
- Validación centralizada en HermesConfig.endpointValidationError().

### Próximo paso opcional
Slice 3.2: WorkManager + outbox en Room + firma HMAC (pendiente aprobación humana para secretos).

---

## Iteration 11 — 2026-10-27 — Slice 3.2.1: OutboxEntity + OutboxDao (COMPLETADO)

### Tarea
Crear entidad OutboxEntity y DAO en Room para rides pendientes de enviar a Hermes. Base del sistema de reintento.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (88 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK generado) |

### Archivos nuevos
- `data/local/entity/OutboxEntity.kt` — entidad Room `outbox`, índices en status/createdAt/rideId
- `data/local/dao/OutboxDao.kt` — insert, getPendingOutbox, markSent, markFailed, deleteSent, countPending
- `test/data/local/dao/OutboxDaoTest.kt` — 9 tests Robolectric con Room in-memory

### Archivos modificados
- `data/local/AppDatabase.kt` — OutboxEntity agregada, version = 2, outboxDao()
- `data/repository/RideRepository.kt` — OutboxDao inyectado, enqueueOutbox(rideId, payload)
- `di/DatabaseModule.kt` — provideOutboxDao
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — Robolectric + AndroidX Test Core (test-only)
- `test/domain/ride/session/TrackingManagerTest.kt` — FakeOutboxDao agregado (constructor de RideRepository cambió)

### Observaciones (pendientes)
- **Sin migración Room 1→2**: version bump sin Migration(1,2) crashea en dispositivos con DB v1 existente. Seguir en 3.2.2 o slice dedicado.
- **Retry semantics gap**: markFailed pone status='FAILED', pero getPendingOutbox solo selecciona PENDING. Los fallidos nunca se reintentan. Corregir en slice siguiente (markFailed debe mantener PENDING hasta agotar maxAttempts, o getPendingOutbox debe incluir FAILED elegibles).

### Próximo paso
Slice 3.2.2: ConnectivityChecker (health check que devuelve 200 cerrado).

---

## Iteration 12 — 2026-10-27 — Fix outbox retry + migración Room (COMPLETADO)

### Tarea
Corregir los dos gaps del Slice 3.2.1: FAILED nunca se reintentaba y faltaba migración Room 1→2.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (92 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `OutboxDao.getPendingOutbox`: `status='PENDING' AND attemptCount < maxAttempts AND nextAttemptAt <= now`.
- `OutboxDao.markFailed`: incrementa attemptCount y deriva `FAILED` solo al agotar `maxAttempts`; si no, sigue `PENDING` con backoff.
- `data/local/Migrations.kt`: `MIGRATION_1_2` crea tabla `outbox` + índices con SQL verificado contra el esquema generado por Room.
- `di/DatabaseModule.kt`: `.addMigrations(MIGRATION_1_2)`, sin destructive fallback.
- Tests nuevos: retry bajo máximo, agotamiento, exclusión por backoff futuro, migración v1→v2 real.

### Notas
- `countPending` cuenta todas las PENDING (gauge de trabajo), no solo elegibles; documentado en KDoc.
- Test existente de markFailed actualizado a la semántica correcta (no debilitado: se agregaron aserciones de elegibilidad).

### Próximo paso
Slice 3.2.2: ConnectivityChecker (health check 200 cerrado).

---

## Iteration 13 — 2026-10-27 — Slice 3.2.2: ConnectivityChecker (COMPLETADO)

### Tarea
Health check previo al envío: solo HTTP 200 exacto cuenta como backend disponible; cualquier otro resultado deja el ride en outbox.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (99 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/ConnectivityChecker.kt` — puerto puro `isReachable(url): Boolean`.
- `domain/sync/HermesConfig.kt` — `healthCheckUrl` opcional + `effectiveHealthCheckUrl()`.
- `sync/HttpConnectivityChecker.kt` — HttpURLConnection en Dispatchers.IO, timeouts 5 s configurables, **`responseCode == 200` estricto**, redirects deshabilitados, sin excepciones al caller.
- `test/sync/HttpConnectivityCheckerTest.kt` — 7 tests con HttpServer efímero (200/204/500/302/malformada/rechazada/timeout).

### Decisiones
- Redirects deshabilitados: sin eso un 302 terminaría en 200 y violaría "200 cerrado".
- Cualquier IOException/SecurityException → false (el ride se guarda y reintenta).

### Próximo paso
Slice 3.2.3: HmacSigner con Android Keystore (aprobación humana ya otorgada para el bloque 3.2).

---

## Iteration 14 — 2026-10-27 — Slice 3.2.3: HmacSigner + Android Keystore (COMPLETADO)

### Tarea
Firma HMAC-SHA256 de payloads de sync con secreto en Android Keystore, formato ADR-003 (`X-Webhook-Signature-V2`, hex de `<timestamp>.<body>`).

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (107 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/HmacSigner.kt` — port sign/verify (KDoc cita ADR-003).
- `domain/sync/HmacKeyProvider.kt` — port de clave (SecretKey JDK).
- `sync/HmacSha256Signer.kt` — HMAC-SHA256, **hex minúsculas**, verify en tiempo constante, hex inválido → false.
- `sync/AndroidKeystoreHmacKeyProvider.kt` — AndroidKeyStore, alias `buzzbike_hmac_v1`, HMAC_SHA256, SIGN|VERIFY, sin auth de usuario (sync en background), lock de generación.
- `di/SyncModule.kt` — providers Hilt.
- `test/sync/HmacSha256SignerTest.kt` — 8 tests; vector RFC 4231 TC2 confirmado (`5bdcc146...c3843`).

### Decisiones
- Se corrigió Base64 → hex para compatibilidad real con Hermes/ADR-003.
- El secreto nunca se exporta ni se loguea; `getEncoded()` no se usa sobre la clave de Keystore.
- `sign` emite minúsculas; `verify` tolera mayúsculas.

### Próximo paso
Slice 3.2.4: HermesWorker (WorkManager) con health check 200, envío firmado, y reintento/outbox.

---

## Iteration 15 — 2026-10-27 — Slice 3.2.4a: OutboxProcessor (COMPLETADO)

### Tarea
Lógica pura del outbox: health check 200 → enviar pendientes → marcar enviado o reprogramar con backoff.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (118 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/OutboxStore.kt` — puerto + OutboxItem.
- `domain/sync/BackoffPolicy.kt` — ExponentialBackoff (base 30 s, max 1 h, clamp exponente 20).
- `domain/sync/OutboxProcessor.kt` — ProcessOutcome + flujo: ConfigurationError / BackendUnavailable / NoPending / Sent / Partial. Excepciones por ítem capturadas (CancellationException re-lanzada).
- `data/local/RoomOutboxStore.kt` — adaptador del DAO al puerto.
- Tests: OutboxProcessorTest (6) + ExponentialBackoffTest (5).

### Decisiones
- Si el health check no da 200, NO se toca el outbox: las filas quedan pendientes para el próximo intento.
- Backoff exponencial con cap; no es constante fisiológica (no requiere ADR).
- Binding Hilt OutboxStore→RoomOutboxStore pendiente hasta que exista consumidor (Worker).

### Próximo paso
Slice 3.2.4b: transporte HTTP real (HttpHermesSender con headers HMAC) y luego 3.2.4c: Worker + WorkManager.

---

## Iteration 16 — 2026-10-27 — Slice 3.2.4b: HttpHermesSender (COMPLETADO)

### Tarea
Transporte HTTP real del puerto HermesSender con headers HMAC ADR-003.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (124 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `sync/HttpHermesSender.kt` — HttpURLConnection, headers `X-Webhook-Timestamp`, `X-Webhook-Signature-V2` (hex), `X-Ride-Id`; éxito de envío = 2xx; redirects off; errores → SendResult sin lanzar ni filtrar URL.
- `sync/HermesSenderProvider.kt` — factory Hilt-inyectable que usa el HmacSigner real.
- `test/sync/HttpHermesSenderTest.kt` — 6 tests con HttpServer efímero (200, 500, 204, 302, rechazada, firma variable).

### Decisiones
- Health check = 200 estricto; envío = 2xx. Diferencia documentada en KDoc.
- Stubs `HermesSenderFactory` intactos; el Worker usará el provider real.

### Próximo paso
Slice 3.2.4c: HermesWorker (WorkManager) + scheduler con constraints de red.

---

## Iteration 17 — 2026-10-27 — Slice 3.2.4c: HermesWorker + Scheduler (COMPLETADO)

### Tarea
Worker de WorkManager que ejecuta OutboxProcessor con constraint de red y scheduler para encolarlo.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (136 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- WorkManager 2.10.5 (`work-runtime-ktx`) en catálogo y build.
- `sync/HermesConfigCodec.kt` — encode/decode puro a Map.
- `sync/SyncAction.kt` — mapeo ProcessOutcome → SUCCESS/RETRY/FAILURE.
- `sync/HermesWorkerEntryPoint.kt` — accessors Hilt para worker.
- `sync/HermesWorker.kt` — CoroutineWorker: decode config → sender real → OutboxProcessor → Result.
- `sync/HermesSyncScheduler.kt` — unique work KEEP, CONNECTED, backoff exponencial 30 s.
- `di/SyncModule.kt` — providers ConnectivityChecker y OutboxStore.
- Tests: HermesConfigCodecTest (7), SyncActionTest (5).

### Decisiones
- Worker no loguea payloads ni URLs.
- `healthCheckUrl` null se codifica "" y vuelve a null (round-trip).
- Worker/scheduler no se testean en JVM (requieren Android); documentado.

### Próximo paso
Slice 3.2.5: integración con RideSessionManager (al detener ride, serializar y encolar en outbox si sync habilitado).

---

## Iteration 18 — 2026-10-27 — Slice 3.2.5a: Ride sync payload (COMPLETADO)

### Tarea
Payload de sync con data minimization (ADR-003): métricas agregadas + waypoints decimados, serializer JSON puro sin dependencias.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (147 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/RideSyncPayload.kt` — modelo versionado + Waypoint.
- `domain/sync/RideSyncInput.kt` — entrada desacoplada de data/android.
- `domain/sync/RidePayloadBuilder.kt` — orden, decimación por intervalo (10 s), cap 2000 conservando extremos, validación de coordenadas, fallback MSL→elipsoidal, redondeo 6 decimales.
- `domain/sync/RidePayloadJson.kt` — encoder JSON compacto UTF-8 con escaping manual; NaN/Inf → null.
- Tests: RidePayloadBuilderTest (6), RidePayloadJsonTest (5).

### Decisiones
- Sin kotlinx.serialization ni org.json: encoder manual testeado (evita plugin/dependencia nueva).
- timestampMillis se reconstruye relativo a startTimeMillis (TrackPoint no tiene epoch); documentado.
- No se envía track crudo: solo waypoints decimados.

### Próximo paso
Slice 3.2.5b: coordinador + integración con RideSessionManager/ViewModel y scheduler.

---

## Iteration 19 — 2026-10-27 — Slice 3.2.5b: Integración sync (COMPLETADO)

### Tarea
Al detener un ride, si el sync está configurado: serializar payload, encolar en outbox y programar worker.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (157 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/RideSyncDataSource.kt`, `OutboxEnqueuer.kt`, `SyncWorkScheduler.kt` — puertos.
- `domain/sync/RideSyncCoordinator.kt` — Disabled/NoData/Enqueued/Failed; config null no toca datos.
- `data/repository/RideSyncDataSourceImpl.kt` — RideDao + TrackPointDao → RideSyncInput.
- `data/local/RoomOutboxEnqueuer.kt` — inserta OutboxEntity PENDING.
- `sync/WorkManagerSyncScheduler.kt` — delega en HermesSyncScheduler.
- `di/SyncModule.kt` — providers de puertos + coordinador.
- `ui/ride/RideViewModel.kt` — configureSync + envío al detener (off por defecto).
- Tests: RideSyncCoordinatorTest (7), RideSyncDataSourceImplTest (3).

### Notas
- `stopCount`/`gapCount`/`rejectedPoints` no están en RideEntity: se envían 0 (documentado).
- Duplicación menor: RideRepository.enqueueOutbox y RoomOutboxEnqueuer insertan lo mismo; unificar en slice posterior.

### Próximo paso
Gate final tester + security-reviewer sobre el diff de sync (9d608bb..HEAD).

---

## Iteration 20 — 2026-10-27 — Gate final bloque sync: FAIL / ESCALADA

### Resultado
| Gate | Veredicto |
|---|---|
| Build/tests | PASS (157 tests, 0 failures, ktlint/detekt/assembleDebug OK) |
| tester | **FAIL** |
| security-reviewer | **FAIL** |

### Hallazgo que fuerza escalada (alto)
**Secreto HMAC sin ruta de aprovisionamiento E2E.** La clave se genera dentro de
Android Keystore y nunca se exporta; el receptor Hermes necesita la MISMA clave
para verificar. No existe flujo (pegar secreto, pairing, QR, export) ni UI de
configuración. Si el receptor verifica, todo el sync falla; si no verifica, no
hay autenticidad/integridad real. Requiere decisión humana de arquitectura.

### Hallazgos code-fixables (tester + security)
1. [alta] Outbox drena solo un batch de 10 y `Sent` → SUCCESS: filas PENDING
   quedan huérfanas. `ExistingWorkPolicy.KEEP` puede ignorar rides nuevos.
2. [media] Sin dedupe por `ride.id` (ADR-003); `Index(rideId)` no único;
   `X-Ride-Id` viaja fuera de la firma.
3. [media] `timeoutSeconds`/`retryCount`/`senderType` de HermesConfig no se aplican.
4. [media] Payload calórico sin modelo ni intervalo (AGENTS §6.4, ADR-003).
5. [media] Filas SENT nunca se purgan; retención indefinida de waypoints.
6. [media/hipótesis] Health check por defecto hace GET al webhook POST → 405 y
   `BackendUnavailable` permanente si no hay `healthCheckUrl` dedicado.
7. [baja] URL permite http/loopback y el sender real no valida; posible URL con
   credenciales en inputData de WorkManager.
8. [baja] Permisos declarados de más (background location, activity recognition,
   FGS health, BLE).
9. [baja] Robolectric 4.16.1 → BouncyCastle 1.81 con CVE-2026-5598 (test-only);
   sin lockfile de dependencias.
10. [baja] Doble stop no idempotente; RideSyncOutcome descartado.
11. [baja] Código duplicado/muerto: RideRepository.enqueueOutbox, HermesSenderFactory/stubs.
12. [baja] `lintDebug` falla por 3 NonObservableLocale preexistentes en RideScreen.

### Estado
**ESCALADA** — esperando decisión humana sobre aprovisionamiento del secreto HMAC
(y opcionalmente cifrado en reposo). No se commiteó nada nuevo.

### Próximo paso propuesto
R1 drenaje completo; R2 dedupe + idempotencia; R3 purga SENT + worker periódico;
R4 honrar config; R5 payload calórico con rango/modelo; R6 healthCheckUrl dedicado;
R7 validación https/loopback; R8 inputData sin credenciales; R9 permisos;
R10 pin BouncyCastle + lockfile; R11 lint; R12 deuda de test de Keystore.

### Decisión humana (2026-10-28)
- **A**: secreto HMAC definido en el NAS y pegado en la app; se sella con clave AES de Android Keystore (envelope encryption).
- **SQLCipher ahora** para no acumular deuda técnica.
- Health check: endpoint dedicado documentado; la auth (HMAC) aplica tanto a API como a webhook.

### Plan de remediación
S1 secreto (A) → S2 SQLCipher → S3 health check autenticado + contrato → R1..R12.

---

## Iteration 21 — 2026-10-28 — S1: Aprovisionamiento secreto HMAC (COMPLETADO)

### Tarea
Opción A: secreto definido en el NAS, pegado en la app, sellado con AES-GCM de Android Keystore.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (165 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/SecretSealer.kt`, `SecretStorage.kt`, `HmacSecretManager.kt`, `HmacSecretMissingException.kt`.
- `sync/AndroidKeystoreSecretSealer.kt` — AES-256-GCM, alias `buzzbike_secret_sealer_v1`.
- `sync/SharedPrefsSecretStorage.kt` — solo blob sellado Base64, commit verificado.
- `sync/StoredHmacKeyProvider.kt` — unseal → SecretKeySpec HmacSHA256; sin secreto lanza excepción.
- `di/SecretModule.kt` — bindings; `AndroidKeystoreHmacKeyProvider` eliminado.
- `sync/HttpHermesSender.kt` — secreto ausente → SendResult(false) sin lanzar.
- Tests: HmacSecretManagerTest (5), StoredHmacKeyProviderTest (3).

### Decisiones
- Secreto mínimo 32 bytes; array de entrada se limpia tras importar.
- Persistencia: solo AES-GCM(secreto) en SharedPreferences privadas; la clave AES no sale del Keystore.
- DI separada en SecretModule por límite TooManyFunctions de detekt.
- Base64 corrupto se trata como “sin secreto” para no bloquear el worker.

### Próximo paso
S2: SQLCipher (DB cifrada, passphrase sellada por Keystore, limpieza de DB plana legacy).

---

## Iteration 22 — 2026-10-28 — S2: SQLCipher + dedupe + limpieza legacy (COMPLETADO)

### Tarea
Cifrar Room con SQLCipher 4.9.0; passphrase aleatoria sellada por Keystore; borrar DB plana legacy; dedupe por rideId.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (174 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 (APK con libsqlcipher.so en 4 ABI) |

### Cambios
- SQLCipher 4.9.0 (`net.zetetic:sqlcipher-android`) + `System.loadLibrary("sqlcipher")` en Application.
- `DatabasePassphraseProvider` — 32 bytes SecureRandom, sellada con SecretSealer, solo blob en SharedPreferences; corrupta → fail-closed.
- `LegacyDatabaseCleaner` — borra `buzzbike.db` siempre que exista (aunque exista la cifrada).
- DB `buzzbike-encrypted.db` con `SupportOpenHelperFactory`; sin destructive fallback.
- `MIGRATION_2_3` + índice único `rideId`; dedupe conserva la fila más antigua.
- `OutboxDao.insertIfAbsent` (IGNORE) y `RoomOutboxEnqueuer` lo usa.
- Tests: DatabasePassphraseProviderTest, MigrationsTest v2→v3, OutboxDaoTest dedupe, cleaner.

### Notas
- La passphrase no se zeroiza: SQLCipher la retiene para reabrir la DB (documentado).
- Pérdida de datos de la DB plana legacy aceptada por ser pre-release; documentado que una release real requiere export/import.
- SQLCipher nativo no se testea en JVM; requiere instrumentación.

### Próximo paso
S3: health check autenticado + URL dedicada + contrato documentado (API y webhook), luego R1..R12.

---

## Iteration 23 — 2026-10-28 — S3: Health check autenticado + contrato (COMPLETADO)

### Tarea
Health check dedicado y autenticado con HMAC (auth en API y webhook), sin fallback al endpoint, y contrato de sync documentado.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (198 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `ConnectivityChecker` con `Reachability` (Available/Unavailable/Misconfigured).
- `HttpConnectivityChecker`: GET firmado (HMAC body vacío), 200 estricto, redirects off; sin secreto/URL inválida → Misconfigured.
- `HermesConfig`: healthCheckUrl dedicado (sin fallback), HTTPS obligatorio, sin userinfo; CGNAT/MagicDNS permitidos.
- `OutboxProcessor.process(url, hmacKeyConfigured)`; Misconfigured → ConfigurationError.
- `HermesWorker`: exige health URL, valida URLs, consulta hasSecret.
- `HttpHermesSender`: valida endpoint, timeouts desde config (mín. 1 s).
- `HermesConfigCodec`: health_check_url obligatorio.
- `docs/sync-contract.md` + actualización ADR-003.
- Tests: 24 nuevos/adaptados (198 total).

### Notas
- El checker no impone HTTPS a nivel transporte; la restricción vive en la validación de config (worker la aplica) y en network_security_config. Pendiente R7 endurecerlo.
- Keystore TLS de test (`test-tls.p12`, autofirmado) agregado en test resources; evaluar generarlo en runtime.

### Próximo paso
R1/R3: drenaje completo del outbox, purga de SENT, worker periódico. Luego R5, R7-R12.

---

## Iteration 24 — 2026-10-28 — R1/R3: drenaje, purga y periódico (COMPLETADO)

### Tarea
Drenar el outbox completo (no solo 10), purgar SENT y FAILED viejos, worker periódico, y sacar X-Ride-Id del wire.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (206 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `OutboxProcessor` drena por batches hasta vaciar; `maxBatches=100`, guarda de no-progreso, `now` por batch; `MorePending` si queda elegible.
- Purga: `deleteSent()` + `deleteFailedOlderThan(now - 7 días)` en cada run.
- `OutboxProcessorConfig(batchSize, maxBatches, failedRetentionMillis)`.
- `OutboxStore`/`RoomOutboxStore`/`OutboxDao`: deleteSent y deleteFailedOlderThan.
- `SyncAction`: MorePending → RETRY.
- `HermesSyncScheduler.schedulePeriodic` (15 min, UPDATE) y `WorkManagerSyncScheduler.schedule` asegura el periódico.
- `HttpHermesSender`: eliminado header X-Ride-Id (el rideId va en el body firmado); docs actualizados.
- Tests: 206 total (+8), incluidos 11/25 items, purga, maxBatches y no-progreso.

### Notas
- La purga no corre en los retornos tempranos ConfigurationError/BackendUnavailable (no hay drenaje).
- Worker periódico no testeable en JVM; documentado.

### Próximo paso
R5: payload calórico con modelo e intervalo; luego R7/R8 (scheme/credenciales), R9-R12.

---

## Iteration 25 — 2026-10-28 — R5: calorías con modelo e intervalo (COMPLETADO)

### Tarea
Persistir y transportar calorías como banda de producto + modelo, nunca como número exacto; UI con rango.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin | 0 |
| test | 0 (219 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `CalorieSummary` + `CalorieResult.toSummary()` (banda simétrica con el mayor %; low ≥ 0; no finitos → null).
- `RideEntity`: `caloriesLowKcal`, `caloriesHighKcal`, `calorieModel`; `MIGRATION_3_4`; DB v4.
- `RideSessionWriter`/`RideRepository`/`RideSessionManager`/`RideSessionUiState`: propagan estimate/low/high/model.
- `RideSyncPayload.calories: CaloriesPayload?` (objeto anidado) y JSON con `calories` o null.
- `RidePayloadBuilder`/`RideSyncDataSourceImpl`: solo emiten calorías si hay estimate+low+high+model.
- `RideScreen`: “≈X kcal (rango L–H; modelo M)” o “--”.
- Tests: 219 total (+13), incluida migración 3→4.

### Notas
- La banda usa el mayor porcentaje (30/40 sin perfil; 20/30 con perfil) en ambos lados; es banda de producto, no IC.
- Rides previos sin modelo → `calories = null` en el payload (no se inventa).

### Próximo paso
R7/R8: endurecer scheme del checker y sacar credenciales del inputData; luego R9-R12.

---

## Iteration 26 — 2026-10-28 — H1: endurecimiento (COMPLETADO)

### Tarea
HTTPS obligatorio, URLs sin credenciales/query, permisos mínimos, stopRide idempotente, retryCount aplicado, senderType documentado, lint verde.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin + test compile | 0 |
| test | 0 (232 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |
| lintDebug | 0 |

### Cambios
- `OutboxProcessor`: exige `https://` (defensa en profundidad) y mantiene blank → ConfigurationError.
- `HermesConfig`: rechaza userinfo, query y fragment en endpoint/healthCheckUrl.
- Manifest: removidos background location, activity recognition, FGS health, BLE y high sampling; quedan FINE/COARSE, FGS, FGS_LOCATION, POST_NOTIFICATIONS, INTERNET/NETWORK_STATE.
- `RideViewModel.stopRide`: guard `stopInFlight` + test de doble stop.
- `OutboxEnqueuer.enqueue(..., maxAttempts)`; coordinator pasa `retryCount` (coerce ≥1); test de persistencia 7/1 y dedupe.
- `senderType` documentado como semántico.
- `RideScreen`: locale observable (`LocalConfiguration`), lint verde.
- Tests: 232 total (+13).

### Próximo paso
H2: BouncyCastle 1.84 (test), lockfile si es viable, limpieza de código muerto y deuda de test de Keystore; luego gate final tester+security.

---

## Iteration 27 — 2026-10-28 — H2: cadena de suministro y limpieza (COMPLETADO)

### Tarea
BouncyCastle 1.84, eliminación de código muerto, lockfiles y documentación de deuda de instrumentación.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin + test compile | 0 |
| test | 0 (221 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |
| lintDebug | 0 |

### Cambios
- `configurations.configureEach { resolutionStrategy.force("org.bouncycastle:bcprov-jdk18on:1.84") }` (CVE-2026-5598 test-only).
- Eliminados `HermesSenderFactory`, `WebhookHermesSender`, `RestHermesSender` y sus 11 tests (código muerto; el real es HttpHermesSender).
- Eliminado `RideRepository.enqueueOutbox` y su `OutboxDao` (duplicaba RoomOutboxEnqueuer).
- KDoc de capas actualizado (DataLayer/SensorLayer/SyncLayer).
- `docs/testing.md` con deuda de instrumentación (SQLCipher real, Keystore, Worker, FGS/permisos, Tailscale).
- Dependency locking: `app/gradle.lockfile` + `settings-gradle.lockfile`.
- Tests: 221 (232 − 11 de código muerto).

### Notas
- El lockfile fija bcprov 1.84; bcpkix/bcutil 1.80.2 quedan para androidLintTool (no es bcprov).
- Lockfiles verificados con build limpio.

### Próximo paso
Gate final tester + security-reviewer sobre el diff de remediación (d4494d2..HEAD + S1-S3).

---

## Iteration 28 — 2026-10-28 — S4a: configuración de sync (COMPLETADO)

### Tarea
Store persistente de HermesConfig + ViewModel de ajustes + RideViewModel leyendo el store.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin + test compile | 0 |
| test | 0 (233 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |

### Cambios
- `domain/sync/SyncConfigStore.kt` (current/save/clear).
- `data/settings/SharedPrefsSyncConfigStore.kt` (prefs `sync_config_v1`, commit verificado).
- `ui/settings/SyncSettingsViewModel.kt` — valida URLs, importa secreto 32+, disconnect.
- `di/SettingsModule.kt`.
- `RideViewModel` inyecta el store; `stopRide` usa `syncConfig ?: store.current()`.
- Tests: SharedPrefsSyncConfigStoreTest (4), SyncSettingsViewModelTest (8).

### Notas
- El secreto nunca entra al estado del ViewModel ni a prefs; solo `HmacSecretManager`.
- `load()` marca isSaved si hay config persistida.

### Próximo paso
S4b: pantalla Compose de ajustes + navegación.

---

## Iteration 29 — 2026-10-28 — S4b: pantalla de Sync (COMPLETADO)

### Tarea
Pantalla Compose mínima para configurar endpoint, health check, retry y secreto; guardar/desconectar; navegación.

### Resultado del gate
| Comando | Exit |
|---|---|
| compileDebugKotlin + test compile | 0 |
| test | 0 (233 tests, 0 failures) |
| ktlintCheck | 0 |
| detekt | 0 |
| assembleDebug | 0 |
| lintDebug | 0 |

### Cambios
- `ui/settings/SyncSettingsScreen.kt`: Scaffold/TopAppBar, campos URL/retry, secreto con PasswordVisualTransformation en estado local (nunca en el VM), ayuda, error/confirmación, Guardar/Desconectar/Volver.
- `NavGraph`: rutas ride/settings con launchSingleTop.
- `RideScreen`: parámetro onOpenSettings + botón “Sync settings”.
- Sin dependencias nuevas; sin iconos (TextButton Atrás).

### Notas
- El secreto se limpia al guardar; no se loguea.
- El flujo de configuración ahora es alcanzable desde la app (resuelve el bloqueo de “sin caller”).

### Próximo paso
Gate final tester + security-reviewer sobre el diff de remediación.
