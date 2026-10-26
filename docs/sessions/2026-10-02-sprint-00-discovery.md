# Sesión 2026-10-02 — Sprint 00: Discovery / Scoping

- **Fecha:** 2026-10-02
- **Tipo:** Discovery (greenfield, sin código)
- **Estado al cerrar:** Documentación base creada; build bloqueado por entorno (sin JDK/SDK)

---

## Resumen

Sesión de arranque del proyecto **BuzzBike** — app Android open source (MIT) de trackeo de rutas de bicicleta. No se escribió código de producción. El trabajo fue: investigación paralela, decisiones de scope, y creación de la documentación base del repo (AGENTS.md, ADRs, formato, README, LICENSE, índice de research).

---

## Investigación realizada (3 researchers en paralelo)

### researcher #1 — Sensores Android + cálculo calórico

Hallazgos clave:

- **Foreground Service** con types `health|location` es obligatorio para trackeo continuo en Android 14+.
- Sin permiso `HIGH_SAMPLING_RATE_SENSORS`, Android 12+ capea acelerómetro/gyro/magnetómetro a **200 Hz** (`minDelay` mínimo 5000 µs). Para cadencia o proxy de vibración no hace falta más.
- `FusedLocationProviderClient` con `PRIORITY_HIGH_ACCURACY` basta para v1. **Raw GNSS / PPP no va al MVP**: sin correcciones externas el resultado puede ser peor que el del propio chipset.
- **Activity Recognition** (`ON_BICYCLE`) sirve como gate para iniciar/detener la sesión de tracking.
- El **barómetro** da delta de altitud relativo mucho más confiable que la altitud GPS (que es pésima en el eje vertical). El desnivel positivo/negativo requiere suavizado e histerésis.
- **Calorías — hallazgo central:**
  - Keytel 2005: ecuación pública, `r=0.913` con VO2max, `r=0.857` sin VO2max. Reproducible.
  - Relojes comerciales vs calorimetría: **error de 9–43%** (Wallen 2016, PLOS One).
  - Compendium 2024: MTB 8.5 MET; road 12–13.9 mph = 8.0 MET; 14–15.9 mph = 10.0 MET; >20 mph = 16.8 MET.
  - ACSM: `VO2 = 1.8*(W*6.12/masaKg) + 7` — **requiere potenciómetro**.
  - "35 kcal/km" **no es constante fisiológica**: es heurística de app genérica. La energía real depende de potencia × tiempo, no de distancia.
- **MediaPipe Pose en background: no viable** (cámara + FGS `camera` + batería).
- **ANT+: descartado** del camino crítico (requiere dongle USB; minoría de teléfonos). BLE es el estándar: HR `0x180D`, CSC `0x1816`, Power `0x1818`.
- **No existe paper público de Meta/Google sobre su algoritmo de calorías.** Prohibido inventar la cita.

### researcher #2 — Hermes Agent / integración webhook

Hallazgos clave:

- **Hermes Agent** (framework, **no** el modelo LLM) existe: https://github.com/NousResearch/hermes-agent — MIT, Python.
- Webhooks HTTP nativos en **puerto 8644**, endpoint `POST /webhooks/<route>`.
- Firma **HMAC V2**: header `X-Webhook-Signature-V2` = HMAC-SHA256 de `<timestamp>.<body>`, ventana ±300 s.
- Idempotencia por delivery ID (cache 1 h en el receptor), rate limit 30 req/min, body limit 1 MB, modo `deliver_only: true`.
- El **modelo Hermes** (Hermes 3 8B / Hermes 4.x) es otra cosa: open-weights en HuggingFace. Los docs del framework desalientan usar Hermes 4 dentro del agente (no es tool-calling).
- Patrón recomendado para la app: **Outbox en SQLite/Room + WorkManager → POST firmado**.
- Payload de un ride de 2 h en JSON compacto + gzip ≈ 100–250 KB (cabe en 1 MB sin chunking).
- **Data minimization:** enviar métricas + waypoints decimados, no el track crudo.
- El cache de idempotencia dura 1 h → el receptor debe dedupear por `ride.id` además del header.

### researcher #3 — Mapas, formatos, wear tracking

Hallazgos clave:

- **MapLibre Native Android** (BSD) muy activo (v13.x). **Usar.**
- **osmdroid está ARCHIVADO** (nov 2024). No usar.
- **OpenFreeMap** — vector tiles, sin API key, sin límites. Requiere atribución visible `© OpenStreetMap contributors` + `© OpenMapTiles`.
- **tile.openstreetmap.org prohíbe offline/prefetch** y bloquea apps por User-Agent genérico. No usarlo como tile source.
- Google Maps / HERE / MapTiler: incompatibles con app MIT open source (ToS).
- **Offline (futuro):** `pmtiles://file://$path` nativo en MapLibre ≥11.7.0. `pmtiles://asset://` no funciona (hay que copiar a `filesDir`).
- **Formatos de exportación:** GPX 1.1 + TrackPointExtension (hr/cad/dist). **FIT = licencia Garmin propietaria**, no usar como formato nativo.
- **Prior art de wear tracking:** `higorslva/biketrackd` (MIT, Android Kotlin Compose, MapLibre + OpenFreeMap, wear % + alerts) — casi idéntico al proyecto. Verificar antes de reinventar.
- No hay schema estándar de intercambio de componentes entre apps.

---

## Objeciones a premisas originales (resueltas con el usuario)

1. **"Cálculo exacto de calorías"** — no existe con sensores de teléfono. El techo realista sin potenciómetro es ±25–35%; con potenciómetro ±5–10%. Objetivo honesto: *el mejor estimador disponible y auditable, con incertidumbre declarada en la UI*.
2. **"Usar la mayoría de sensores"** — más sensores ≠ más precisión. Aportan (en orden): HR > potenciómetro > barómetro > GPS. NO aportan o son contraproducentes en background: magnetómetro (interferencia), linear acceleration (derivado), gravity (derivado). La IMU sirve para *detectar movimiento*, no para medir esfuerzo fisiológico.
3. **"35 kcal/km"** — heurística, no constante. La energía depende de potencia × tiempo. Dos rides con la misma potencia y distinta distancia gastan lo mismo.
4. **"Backend local"** — acuerdo total: el cálculo (calorías, wear, stats) es 100% on-device. Lo que va al agente es un **reporte**, no lógica de negocio.
5. **"Mapas freemaps"** — interpretado como mapas libres/open source. Online primero; offline PMTiles diferido.

---

## Decisiones tomadas

| # | Decisión | Valor |
|---|---|---|
| D1 | HR strap / potenciómetro | **No tienen en v1.** Motor calórico con fallback MET; arquitectura preparada para enchufarlos después. |
| D2 | Dónde corre Hermes Agent | **NAS del usuario, alcanzable por Tailscale.** |
| D3 | Mapas | **Online primero:** MapLibre Native + OpenFreeMap. Offline PMTiles diferido. |
| D4 | Distribución | **GitHub Releases (APK).** NO Play Store. F-Droid posible a futuro. |
| D5 | JDK + Android SDK en esta máquina | Pendiente de instalar (~8–12 GB). **Bloqueante para código.** |
| D6 | Nombre de paquete | Placeholder `com.buzzbike.app` — **TODO: confirmar.** |
| — | Stack | Kotlin + Jetpack Compose + Hilt + Room + WorkManager. |
| — | Licencia | MIT. |
| — | Versionado / commits | Conventional Commits. |
| — | Modelo calórico | Cascada 3 niveles ACSM / Keytel / Compendium → **ADR-001**. |
| — | Wear tracking | Máximos normalizados, alertas 80%/100% → **ADR-004**. |
| — | Sync Hermes | Outbox Room + WorkManager + HMAC V2 + Keystore + data minimization → **ADR-003**. |

---

## Artefactos creados en esta sesión

| Archivo | Qué es |
|---|---|
| `AGENTS.md` | Fuente de verdad para agentes: scope, stack, estructura, comandos, reglas críticas (dominio físico, privacy). |
| `docs/adr/ADR-001-modelo-calculo-calorico.md` | Cascada de 3 niveles + reglas de UI de incertidumbre. |
| `docs/adr/ADR-002-mapas-maplibre-openfreemap.md` | MapLibre + OpenFreeMap, atribución ODbL, alternativas descartadas. |
| `docs/adr/ADR-003-hermes-agent-webhook-hmac.md` | Webhook HMAC V2, outbox, data minimization, Keystore. |
| `docs/adr/ADR-004-wear-tracking-maximos-normalizados.md` | Fórmula de wear, umbrales, service events, defaults. |
| `docs/format/README.md` | Índice de convenciones de formato. |
| `docs/format/estilo-kotlin.md` | Estilo Kotlin/Compose: naming, sealed states, coroutines, errores. |
| `docs/sessions/2026-10-02-sprint-00-discovery.md` | Esta bitácora. |
| `README.md` | Intro del proyecto para humanos. Estado WIP. |
| `LICENSE` | MIT, año 2026, copyright holder placeholder. |
| `research/README.md` | Índice de los 3 informes de investigación con sus fuentes. |

---

## Plan de implementación — 8 slices

> Orden sugerido. Cada slice es mergeable a `main` y deja el repo en estado compilable (una vez exista el toolchain).

### Slice 1 — Setup del proyecto Android
- Gradle wrapper, módulo `app`, Kotlin + Compose + Material 3, Hilt, Room, WorkManager.
- Estructura de paquetes `data/`, `domain/`, `ui/`, `sensor/`, `sync/`.
- Config de ktlint + detekt + `.editorconfig`.
- CI mínimo (GitHub Actions): lint + unit tests en JVM.
- **Salida:** `./gradlew assembleDebug test detekt ktlintCheck` verde.

### Slice 2 — Tracking de ride core
- Foreground Service `health|location` + notificación de trackeo activo.
- `FusedLocationProviderClient` (`PRIORITY_HIGH_ACCURACY`) → Flow de lecturas.
- Activity Recognition como gate de sesión.
- Persistencia de track en Room (puntos GPS + timestamps).
- **Salida:** se puede empezar/parar un ride y queda persistido localmente.

### Slice 3 — Sensores inerciales + barómetro
- Adaptadores en `sensor/`: acelerómetro, giroscopio (cap a 200 Hz en Android 12+), barómetro si existe.
- Streams como `Flow<SensorReading>`; aislamiento del API de Android.
- Detección de "hay movimiento" para complementar GPS (no para medir esfuerzo fisiológico).
- **Salida:** lecturas de IMU/barómetro disponibles para el dominio.

### Slice 4 — Motor de descriptores de ride
- En `domain/`: distancia, velocidad media/pico, moving time, desnivel +/-.
- Suavizado e histerésis para desnivel (barómetro como fuente primaria de delta de altitud; GPS como fallback).
- Tests JVM puro con fixtures de tracks conocidos.
- **Salida:** un ride guardado produce sus descriptores calculados.

### Slice 5 — Motor calórico v1 (ADR-001)
- Cascada en `domain/`: nivel 3 Compendium por bandas de velocidad (camino crítico v1); niveles 1 y 2 como hooks listos para HR/potencia futuros.
- BMR Mifflin-St Jeor como componente de reposo.
- Modelo `CalorieEstimate` = kcal + intervalo de incertidumbre + modelo usado.
- Tests = snapshot de valores citados en el Compendium/Keytel.
- **Salida:** el ride tiene estimación calórica con incertidumbre y modelo declarado.

### Slice 6 — UI de ride (live + summary) + mapa
- Compose Material 3: pantalla de tracking en vivo, pantalla de resumen, historial.
- MapLibre Native + OpenFreeMap online; atribución ODbL visible (`© OpenStreetMap contributors` + `© OpenMapTiles`).
- UI del estimador calórico: **siempre** intervalo + modelo, nunca número exacto solo.
- **Salida:** app usable de punta a punta para trackear y ver un ride en el mapa.

### Slice 7 — Wear tracking (ADR-004)
- Tablas Room: `components`, `component_events`.
- Cálculo `wearRatio = max(km/lifecycleKm, hours/lifecycleHours, days/lifecycleDays)` en `domain/`.
- Alertas `approaching` (80%) y `due` (100%).
- Evento `service` con snapshot previo en `component_events`.
- Defaults: cadena 2000 km, suspensión 100 h (50 h parcial), neumáticos 3000–5000 km; frenos por km + desnivel negativo (proxy).
- Revisar prior art `higorslva/biketrackd` antes de codear.
- **Salida:** los componentes de la bici muestran wear % y alertas.

### Slice 8 — Sync Hermes + exportación + pulido de distribución
- Outbox en Room + WorkManager (constraint CONNECTED, backoff exponencial, unique work KEEP).
- Firma HMAC V2 (`X-Webhook-Signature-V2` = HMAC-SHA256 de `<timestamp>.<body>`, ±300 s) con secret en Android Keystore.
- Dedupe por `ride.id`; data minimization (métricas + waypoints decimados, no track crudo).
- Exportación GPX 1.1 + TrackPointExtension.
- Config de release para GitHub Releases (APK firmado); atribución de tiles en la UI de settings/about.
- **Salida:** se puede exportar GPX y, opcionalmente, enviar el reporte al NAS por Tailscale.

---

## Entorno (verificado el 2026-10-02)

| Item | Estado |
|------|--------|
| Repo | `/home/notebook01/Proyectos/BuzzBike` — git inicializado |
| SO | Zorin OS 17.3 (Ubuntu base) |
| JDK | **AUSENTE** — `java: command not found`, sin `/usr/lib/jvm`, sin sdkman |
| Android SDK | **AUSENTE** — `ANDROID_HOME` y `ANDROID_SDK_ROOT` vacíos |
| adb | presente (`/usr/bin/adb`) |
| Python | `/home/notebook01/anaconda3/bin/python3` |
| Disk libre | ~20 GB en `/` (Android SDK + build ≈ 8–12 GB) |

**Bloqueante para cualquier slice de código:** falta JDK 17+ y Android SDK. La documentación de esta sesión no depende del toolchain.

---

## Pendientes / próximos pasos

- [ ] Instalar **JDK 17+** y **Android SDK** (bloqueante para slices 1–8).
- [ ] Confirmar **app id / package name** (`com.buzzbike.app` es placeholder).
- [ ] Definir **nombre definitivo** de la app (BuzzBike es tentativo).
- [ ] Completar **copyright holder** del `LICENSE` (placeholder `[TU NOMBRE]`).
- [ ] Revisar **prior art** `higorslva/biketrackd` antes del slice 7.
- [ ] Crear config de **ktlint + detekt** en el slice 1 (hoy no existen archivos de config).
- [ ] Decidir si el receptor del webhook en el NAS deduplica por `ride.id` además del delivery ID (recomendado: sí, ver ADR-003).
- [ ] Future (no v1): HR strap BLE + potenciómetro; offline PMTiles; F-Droid.

---

## Notas para futuras sesiones

- El `state/loop-state.md` es bitácora del pipeline agéntico y **no se edita a mano**.
- Cualquier cambio a fórmulas de calorías o wear **debe** venir con ADR nuevo o actualización + cita a fuente (AGENTS.md §6).
- Privacy: nada sale del dispositivo sin acción explícita del usuario; data minimization en el sync por defecto.
- La UI **nunca** presenta un número calórico como exacto.
