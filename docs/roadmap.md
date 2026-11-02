# Roadmap — BuzzBike

> Documento de trabajo, no marketing. Sirve para retomar el proyecto en cualquier
> momento sin releer todo el repo. Fuente de verdad del *por qué*: `AGENTS.md` y
> `docs/adr/`. Fuente de verdad del *estado real*: `AGENTS.md` §0 y `docs/sessions/`.
> Última actualización: **2026-11-01**.

---

## 0. Plan de ejecución v1 (macroplan)

> Orden de trabajo vigente a partir de **2026-10-30**, después de cerrar U2. Las
> fases de desarrollo ya ejecutadas (fundación, scaffold, dominio, captura,
> cálculo nivel 3, sync+export, CI) están marcadas en §2; este macroplan cubre el
> tramo que falta hasta una release usable.
>
> **Gate de cada fase de código: `tester: PASS` + `security-reviewer: PASS`.**
> (Alcance del security pass por slice: ver §4 — por slice si toca secretos o
> red, y siempre como gate final de release.)

| Fase | Qué incluye | Gate / cierre |
|---|---|---|
| **0 — Verdad y salvaguardas** | Estado honesto en docs (`README.md`, `AGENTS.md`, este roadmap), resolución de la contradicción de proceso sobre `security-reviewer` (§4), app id y nombre definitivo **pendientes de la persona**, harness de `doc-writer` y gate de seguridad acumulado. | Docs verificados contra el código; §4 unificada con `AGENTS.md`. |
| **1 — Verificación en hardware** ✅ **COMPLETA (2026-11-01)** | Emulador o dispositivo conectado, smoke E2E manual, ejecución de `connectedDebugAndroidTest` (hoy solo compila) y, si entra, emulador en CI. **Hecho con el AVD local `tester36`: 8/8 instrumentados en verde.** | Instrumentados en verde sobre un dispositivo/emulador real. |
| **2 — U3 Ajustes y perfil** ✅ **COMPLETA (2026-11-01, 465 tests)** | **Perfil corporal en Room cifrado (ADR-007)**, **ADR de dato personal** nuevo (ADR-007), y cablear `hasProfile` en ride/historial/detalle (estaba hardcodeado en `false`). | `tester: PASS` + `security-reviewer: PASS`. |
| **3 — U4 Garaje y desgaste** ✅ **COMPLETA (2026-11-01, 551 tests)** | Migración Room **5→6** con tablas `bikes`, `components`, `component_events` + `WearEngine` (ADR-004) + UI de garaje + bici asignada al ride. | `tester: PASS` + `security-reviewer: PASS`. |
| **4 — U5 Mapa** ✅ **COMPLETA (2026-11-01, 576 tests)** | **Se implementó** (la opción de quitar la dependencia quedó descartada): MapLibre + OpenFreeMap online con estilo Liberty, track en el detalle vía pestaña **DATOS/MAPA**, atribución ODbL visible, aviso offline con reintento. Artefacto cambiado a **`android-sdk-opengl:13.7.1`** (ver actualización 2026-11-01 de ADR-002). | `tester: PASS` (576 tests, 0 fallos); `security-reviewer: PASS` (0 altos/críticos; 1 medio de atribución y 2 bajos, ver `state/loop-state.md`). |
| **5 — U6 Accesibilidad y pulido** | TalkBack, contraste, deuda de corrutinas (p. ej. `runCatching` que captura `CancellationException`) y tests de borde. | `tester: PASS` + `security-reviewer: PASS`. |
| **6 — Release** | Keystore + secrets, **release firmada**, **E2E del sync contra un Hermes real**, `security-reviewer` final sobre el diff acumulado, docs `PRIVACY`/`SECURITY`/`CONTRIBUTING`/guía de usuario/inventario de datos y `CHANGELOG` **v1.0.0**. | `security-reviewer` final PASS + tag + GitHub Release firmada. |

> **Nota sobre Fase 2 (U3) — 2026-11-01:** completada. El plan original decía
> "DataStore + perfil corporal"; se implementó **en Room cifrado con SQLCipher**
> para que el perfil comparta la misma frontera de cifrado que los rides
> (motivo y decisiones: **ADR-007**). Gate JVM en **465 tests, 0
> fallos**. La DB pasó a **v5** (migración 4→5: crea `user_profile` y agrega
> las 4 columnas `profile_*` de `rides`); por eso
> la migración de U4 fue **5→6**, no 1→2.

> **Nota sobre Fase 3 (U4) — 2026-11-01:** completada. La DB pasó a **v6**
> (migración **5→6**: tablas `bikes`, `components`, `component_events` +
> `rides.bike_id` nullable con índice, sin FK dura — ver actualización
> 2026-11-01 de ADR-004). Gate JVM en **551 tests, 0 fallos**. La **próxima
> migración sería 6→7**, solo si un slice posterior la requiere (U5 no la
> requirió — ver nota de Fase 4).

> **Nota sobre Fase 4 (U5 Mapa) — 2026-11-01:** completada **implementando** el
> mapa; la alternativa de **sacar la dependencia** quedó descartada (MapLibre
> ya era `ADR-002` y entra en v1). Artefacto cambiado de `android-sdk` a
> **`org.maplibre.gl:android-sdk-opengl:13.7.1`** — en 13.7.1 el `android-sdk`
> es **Vulkan-only**, arriesgado con minSdk 26 — con el renderer **forzado a
> OpenGL**. Release con `abiFilters` solo `arm64-v8a` + `armeabi-v7a` (debug
> conserva `x86`/`x86_64` para el emulador): APK de debug de **~84.5 MB →
> ~70 MB**. **Sin migración Room: la DB sigue en v6.** Gate JVM en **576
> tests, 0 fallos**. Detalle: actualización 2026-11-01 de ADR-002.

> **Nota sobre Fase 6 (2026-10-31):** el pipeline de **release firmado ya está
> preparado en CI** (`.github/workflows/android.yml`, job `release` en tags `v*`:
> `assembleRelease` + `app-release.apk.sha256`, fail-closed sin secrets,
> permisos acotados al job y acciones pinnadas por SHA). Solo falta que la
> persona configure el keystore y los **5 secrets**: los 4 de firma
> `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
> `RELEASE_KEY_PASSWORD`, más **`RELEASE_CERT_SHA256`** (SHA-256 del certificado
> de firma; obligatorio — si falta o no coincide, el job falla antes de publicar;
> obtenerlo con `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
> y tomar el «certificate SHA-256 digest» sin `:`). El job además **rechaza**
> APKs con `android:debuggable` y certificados `CN=Android Debug`.
> El job `build` ya no publica APKs de debug; solo quedan como artifact
> `buzzbike-debug-apk`.

---

## 1. Estado actual

> **Nota breve (2026-11-01).** El estado vigente del proyecto se mantiene en
> **`AGENTS.md` §0**. A la fecha: **U0 (tema/componentes), L1 (pause/resume + FGS),
> U1 (Ride en vivo), U1.1 (permiso de ubicación en runtime), U2
> (historial/detalle + GPX), U3 (Ajustes y perfil), U4 (Garaje y desgaste) y
> U5 (Mapa)** están hechos**; el próximo slice es **U6 (Accesibilidad y
> pulido)**. El resto de este documento
> queda como **referencia de fases y
> criterios**, no como estado real; si hay conflicto, manda `AGENTS.md` §0.

---

## 2. Fases y slices

> Reordenamiento de los 8 slices originales de la sesión de discovery
> (2026-10-02) en fases con dependencias explícitas. Cada slice es mergeable
> a `main` y debe dejar el repo compilable.

### Fase 0 — Fundación (COMPLETA)

- Investigación (3 informes en paralelo: sensores/calorías, Hermes, mapas/wear) → `research/`.
- Docs base: `AGENTS.md`, ADR-001 a ADR-006, `docs/format/`, `LICENSE`, `README.md`.
- Toolchain: **JDK Temurin 21.0.13**, Gradle 9.8.2 (wrapper), **Android SDK 37.0** en `~/android-sdk`
  (build-tools 36.0.0). *Corregido 2026-10-30: decía «JDK 17 (Zulu), Android SDK 36».*

### Fase 1 — Scaffold (COMPLETA)

**Slice 1 — build Gradle + Compose + Hilt + Room + ktlint/detekt.**
Archivos escritos y commiteados (`e54cce2`). **Gate de verificación en verde**
(`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `lintDebug`) — cerrado (el
gate de esa época era de 415 tests; el conteo vigente está en `AGENTS.md` §0).

### Fase 2 — Dominio y datos (COMPLETA)

- **Slice 2 — schema Room:** *estado real 2026-10-30:* las tablas cerradas en v1 son
  `rides`, `track_points`,   `outbox` (+ migración Room 1→2). *Actualizado
  2026-11-01:* U3 agregó **`user_profile`** y las 4 columnas `profile_*` de
  `rides` (migración 4→5). **U4 agregó `bikes`, `components` y
  `component_events`, más `rides.bike_id`** (nullable + índice; migración
  5→6): **la DB está en v6** y todas las tablas del modelo existen.
- **Slice 4 — motor de descriptores:** distancia, moving time, velocidad
  (media/pico), desnivel +/− con suavizado e histéresis (barómetro primario,
  GPS como fallback). ✅ Hecho (ADR-005 segmentación, ADR-006 elevación). Tests JVM puro con fixtures de tracks conocidos.

### Fase 3 — Captura (COMPLETA)

- **Slice 3 — Foreground Service** `location` + `FusedLocationProviderClient`
  (`PRIORITY_HIGH_ACCURACY`) + pipeline de puntos con batching + persistencia en Room. ✅ Hecho
  (incluye permiso de ubicación en runtime — U1.1). **Activity Recognition
  (`ON_BICYCLE`) no se implementó** y queda fuera de v1.
- **Slice 3b (opcional) — sensores inerciales:** acelerómetro/giroscopio (cap 200 Hz
  en Android 12+ sin `HIGH_SAMPLING_RATE_SENSORS`) y barómetro si existe. ✅ Hecho
  (Detección de movimiento, **no** medición de esfuerzo fisiológico).

### Fase 4 — Cálculo (COMPLETA — nivel 3 ✅ / WearEngine ✅ U4)

- **Slice 5 — `CaloriesEngine` (ADR-001):** ✅ **COMPLETA (nivel 3).** Cascada ACSM → Keytel → Compendium MET;
  en v1 solo corre el **nivel 3** (Compendium por bandas de velocidad GPS); los
  niveles 1 y 2 quedan como hooks. BMR Mifflin-St Jeor — **operativo desde U3**
  (2026-11-01): baseline de paradas `REE/1440` con perfil completo, fallback
  `MetTable.STOP_MET` (actualización 2026-11-01 de ADR-001). **UI de incertidumbre
  obligatoria:** intervalo + modelo usado, nunca un número exacto solo.
- **Slice 6 — `WearEngine` (ADR-004):** ✅ **COMPLETA (U4, 2026-11-01).** Implementado en
  `domain/wear/` (JVM puro): tablas `components`/`component_events` en DB v6.
  `wearRatio = max(km/lifecycleKm, hours/lifecycleHours, days/lifecycleDays)`,
  con `lifecycleDays` nullable (eje `null` = no se evalúa). Alertas
  `approaching` (80%) y `due` (100%). Evento `service` con snapshot previo en
  `component_events`. Defaults (`ComponentDefaults`): cadena 2000 km,
  suspensión 100 h, neumáticos 3000 km (mínimo del rango, configurable);
  **frenos sin default** — requieren km configurados por el usuario (el factor
  de desnivel negativo sigue como TODO informativo). Detalle completo: 
  actualización 2026-11-01 de ADR-004.

### Fase 5 — Salida (PARCIAL — sync+export ✅ / mapas ✅ U5; queda el E2E de sync)

- **Slice 7 — Sync + export (ADR-003):** ✅ **COMPLETA.** outbox en Room + WorkManager
  (`CONNECTED`, backoff exponencial, unique work `KEEP`) + firma HMAC V2
  (`X-Webhook-Signature-V2`) con secret en Android Keystore + dedupe por
  `ride.id` + data minimization + export GPX 1.1 con TrackPointExtension (U2, por SAF).
  **Queda pendiente solo el E2E contra un NAS con Hermes real.**
- **Slice 8 — Mapas (ADR-002):** ✅ **COMPLETA (U5, 2026-11-01).** MapLibre Native +
  OpenFreeMap online (**estilo Liberty, sin API key**), track del ride en el
  detalle vía pestaña **DATOS/MAPA**, atribución ODbL visible (`©
  OpenStreetMap contributors` + `© OpenMapTiles` + `OpenFreeMap`, con links, más
  el botón «(i)» de MapLibre habilitado), aviso de offline con `REINTENTAR` y
  timeout de estilo de 9 s. **Artefacto `android-sdk-opengl:13.7.1`** (no
  `android-sdk`: Vulkan-only en 13.7.1), renderer forzado a OpenGL, release solo
  `arm64-v8a` + `armeabi-v7a`. **Sin migración Room (DB en v6).** Detalle:
  actualización 2026-11-01 de ADR-002. **Queda pendiente** verificar el
  User-Agent efectivo de las requests de tiles (§3, «UA identificable»).
- **CI + release workflow** en GitHub (lint + unit tests en JVM; APK). ✅ **COMPLETA** —
  `.github/workflows/android.yml` verde: gate `test ktlintCheck detekt lintDebug
  assembleDebug`, artifact `buzzbike-debug-apk` y Release en tags `v*` (existe `v0.1.0`).
  El job `release` exige **5 secrets** (los 4 de firma + `RELEASE_CERT_SHA256`),
  falla closed sin ellos y además **rechaza** APKs `android:debuggable` y
  certificados `CN=Android Debug`.

### Fase 6 — Release gate (PENDIENTE)

- `security-reviewer` completo sobre **todo el diff acumulado** (ver §4).
- Lint/format final, tag, GitHub Release con APK **firmado** (hoy no hay
  `keystore.properties` ni secrets) + CHANGELOG v1.0.0.
- E2E del sync con Hermes real + docs `PRIVACY`/`SECURITY`/`CONTRIBUTING`/guía de usuario
  (ver macroplan §0, Fase 6).

### Fase 7 — Post-v1 (no comprometido)

- HR strap BLE (Nordic BLE Library, HR `0x180D`) → habilita nivel 2 de la cascada
  calórica (±10–20%).
- Potenciómetro (Cycling Power `0x1818`) → nivel 1, ±5–10%.
- Mapas offline PMTiles (MapLibre ≥11.7.0, `pmtiles://file://$path`).
- F-Droid / build reproducible.
- Import FIT (licencia Garmin; solo import opcional, nunca formato nativo).

---

## 3. Criterios de aceptación por slice

| Slice | Entregable | Cómo se verifica | Dependencias |
|---|---|---|---|
| 1 — Scaffold | Proyecto Gradle compilable con Compose + Hilt + Room + ktlint/detekt | `./gradlew assembleDebug test ktlintCheck detekt lint` sin errores | Toolchain (✅ instalado) |
| 2 — Schema Room | 6 tablas + `exportSchema = true` + migración v1 | `./gradlew test` (compilación del schema + tests de DAO si aplica) | Slice 1 |
| 4 — Descriptores | Motor en `domain/` (JVM puro) que produce distancia/moving time/velocidad/desnivel desde un ride persistido | Tests JVM con fixtures de tracks conocidos (`./gradlew test`) | Slice 2 |
| 3 — FGS + GPS | Ride empezable/parizable desde la app; puntos persistidos; notificación de trackeo activo | Compila + lint de permisos/FGS verde. **No hay instrumented tests** (sin dispositivo/emulador) | Slice 2 |
| 3b — IMU/baró (opcional) | Flujos `Flow<SensorReading>` aislados del API de Android | Tests JVM de la capa de aislamiento | Slice 3 |
| 5 — CaloriesEngine | Estimación calórica con intervalo + modelo en la UI; snapshots testados contra valores citados | Tests JVM con valores del Compendium/Keytel citados en ADR-001 | Slice 4 |
| 6 — WearEngine | `wearRatio` + alertas 80/100% + historial de services | Tests JVM de la fórmula (máximo normalizado, resets, ejes) | Slice 2, 4 |
| 7 — Sync + export | Outbox drenada por WorkManager; firma HMAC V2 válida; GPX 1.1 válido | Tests JVM (firma, dedupe, serialización) + validación de GPX contra fixtures. Receptor real solo si el usuario tiene Hermes + Tailscale listos | Slice 4; Hermes en NAS (opcional, mock si no) |
| 8 — Mapas | Mapa online con track + atribución ODbL visible + UA identificable | Compila + revisión visual manual (requiere dispositivo; fuera de esta máquina) | Slice 3 |
| 6 (release) — Gate final | Diff completo pasa security review; APK de release firmado | `security-reviewer` PASS + tag + GitHub Release | Todos los anteriores |

> **Estado real del schema (2026-11-01):** la fila «2 — Schema Room» habla de
> 6 tablas; hoy existen **7** (`rides`, `track_points`, `outbox`,
> `user_profile`, `bikes`, `components`, `component_events`), con
> **migraciones 1→2 … 5→6** y la DB en **v6**. **U5 (Mapa) no requirió
> migración: la DB sigue en v6.** La próxima migración, si un slice la
> necesita, es **6→7**.

---

## 4. Decisiones de proceso

1. **Gate por slice = `builder` + `tester`.** `builder` implementa; `tester` valida
   y emite PASS/FAIL explícito. Un slice sin PASS no se da por cerrado.
2. **`security-reviewer`: en slices que tocan secretos o red + como gate final de
   release — unificado con `AGENTS.md` §0 (nota fechada 2026-10-30).**
   La decisión original (2026-10-26) de **diferir el security review
   exclusivamente al gate de release** quedó **superada**: manda `AGENTS.md` §0,
   que dice que `security-reviewer` corre (a) en los slices que tocan **secretos o
   red** y (b) como **gate final de release** sobre el diff acumulado. Así se
   ejecutó desde entonces: el slice de sync (secret HMAC + Keystore + red) cerró
   con `security-reviewer` **FAIL → remediación → PASS**, y U1.1 y U2 cerraron con
   **PASS** (0 hallazgos abiertos). A partir del macroplan §0, **cada fase de
   código cierra con `tester: PASS` + `security-reviewer: PASS`**.

   > **Nota (2026-10-30):** este punto decía «`security-reviewer` diferido —
   > gate único de release», lo que contradecía `AGENTS.md` §0 («corre en los
   > slices que tocan secretos o red, y como gate final de release»). Se corrige
   > acá para unificar ambas fuentes; `AGENTS.md` manda.

3. **Deuda de seguridad histórica (slice 7) — saldada.** La decisión de
   2026-10-26 dejó al slice 7 (secret HMAC + Keystore + red) acumulando deuda
   hasta el gate final; ese slice ya pasó por `security-reviewer` con FAIL y
   remediación antes de cerrar. Se mantiene la **cláusula de escape** para todo
   slice futuro: un hallazgo de **severidad crítica** se revisa **en el momento**,
   sin esperar al release.
4. **Regla que NO se relaja (AGENTS.md §6):** ningún cambio en fórmulas de
   dominio físico (calorías, BMR, MET, wear) se acepta sin fuente citada en KDoc
   y ADR nuevo o actualizado. Aplica siempre, independientemente de en qué punto
   esté el security review.

---

## 5. Restricciones y supuestos

- **Sin sensores externos en v1** (sin HR strap, sin potenciómetro) → techo de
  precisión calórica **±30–40% sin perfil / ±20–30% con perfil (ADR-001, bandas
  de producto)**. **No prometer exactitud**; la UI declara el intervalo y el modelo.
- **Distribución: GitHub Releases (APK).** Sin Play Store. F-Droid solo futuro
  con build reproducible.
- **Hermes Agent en el NAS del usuario**, alcanzable por Tailscale. Si no está
  listo al llegar al slice 7, se implementa contra la spec de ADR-003 y se
  valida con un mock receiver local. *(Así pasó: el slice 7 se implementó contra
  spec con mock y cerró; el E2E contra un NAS real sigue pendiente — §0, Fase 6.)*
- **Equipo: una persona + pipeline agéntico.** Sin emulador ni dispositivo
  Android conectado a esta máquina → **no hay instrumented tests**; todo lo
  verificable en CI/máquina es JVM (`test`, `ktlintCheck`, `detekt`, `lint`).
- **Entorno sin sudo** y instalación manual del toolchain; red lenta hacia el
  CDN de Google (el JDK se reinstala desde cualquier mirror de Temurin).
- **App id `com.buzzbike.app` y nombre "BuzzBike" son placeholders** hasta
  confirmación (AGENTS.md §11).
