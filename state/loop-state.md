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
