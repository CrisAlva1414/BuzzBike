# Roadmap — BuzzBike

> Documento de trabajo, no marketing. Sirve para retomar el proyecto en cualquier
> momento sin releer todo el repo. Fuente de verdad del *por qué*: `AGENTS.md` y
> `docs/adr/`. Fuente de verdad del *estado real*: `AGENTS.md` §0 y `docs/sessions/`.
> Última actualización: **2026-10-30**.

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
| **1 — Verificación en hardware** | Emulador o dispositivo conectado, smoke E2E manual, ejecución de `connectedDebugAndroidTest` (hoy solo compila) y, si entra, emulador en CI. | Instrumentados en verde sobre un dispositivo/emulador real. |
| **2 — U3 Ajustes y perfil** | DataStore + perfil corporal, **ADR de dato personal** nuevo, y cablear `hasProfile` en ride/historial/detalle (hoy hardcodeado en `false`). | `tester: PASS` + `security-reviewer: PASS`. |
| **3 — U4 Garaje y desgaste** | Migración Room v1→v2 con tablas `bikes`, `components`, `component_events` + `WearEngine` (ADR-004) + UI de garaje + bici asignada al ride. | `tester: PASS` + `security-reviewer: PASS`. |
| **4 — U5 Mapa** | MapLibre + OpenFreeMap con atribución ODbL visible y track en el detalle; **o sacar la dependencia** si no entra en v1 (hoy está declarada con cero usos de Kotlin). | `tester: PASS` + `security-reviewer: PASS`, o decisión documentada de quitar la dependencia. |
| **5 — U6 Accesibilidad y pulido** | TalkBack, contraste, deuda de corrutinas (p. ej. `runCatching` que captura `CancellationException`) y tests de borde. | `tester: PASS` + `security-reviewer: PASS`. |
| **6 — Release** | Keystore + secrets, **release firmada**, **E2E del sync contra un Hermes real**, `security-reviewer` final sobre el diff acumulado, docs `PRIVACY`/`SECURITY`/`CONTRIBUTING`/guía de usuario/inventario de datos y `CHANGELOG` **v1.0.0**. | `security-reviewer` final PASS + tag + GitHub Release firmada. |

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

> **Nota breve (2026-10-30).** El estado vigente del proyecto se mantiene en
> **`AGENTS.md` §0**. A la fecha: **U0 (tema/componentes), L1 (pause/resume + FGS),
> U1 (Ride en vivo), U1.1 (permiso de ubicación en runtime) y U2
> (historial/detalle + GPX) están hechos**; el próximo slice es **U3 (Ajustes y
> perfil)**. El resto de este documento
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
(`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `lintDebug`) — cerrado; el
gate acumulado hoy es de **415 tests, 0 fallos** (ver `AGENTS.md` §0).

### Fase 2 — Dominio y datos (COMPLETA)

- **Slice 2 — schema Room:** *estado real 2026-10-30:* las tablas cerradas en v1 son
  `rides`, `track_points`, `outbox` (+ migración Room 1→2). `bikes`, `components` y
  `component_events` **no existen** y quedan para U4 (Fase 3 del macroplan §0).
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

### Fase 4 — Cálculo (PARCIAL — nivel 3 ✅ / WearEngine ❌ pendiente)

- **Slice 5 — `CaloriesEngine` (ADR-001):** ✅ **COMPLETA (nivel 3).** Cascada ACSM → Keytel → Compendium MET;
  en v1 solo corre el **nivel 3** (Compendium por bandas de velocidad GPS); los
  niveles 1 y 2 quedan como hooks. BMR Mifflin-St Jeor. **UI de incertidumbre
  obligatoria:** intervalo + modelo usado, nunca un número exacto solo.
- **Slice 6 — `WearEngine` (ADR-004):** ❌ **PENDIENTE (U4).** No hay `domain/wear/` ni
  tablas de componentes.
  `wearRatio = max(km/lifecycleKm, hours/lifecycleHours, days/lifecycleDays)`.
  Alertas `approaching` (80%) y `due` (100%). Evento `service` con snapshot previo
  en `component_events`. Defaults: cadena 2000 km, suspensión 100 h, neumáticos
  3000–5000 km, frenos por km + desnivel negativo (proxy, ponderación = TODO).

### Fase 5 — Salida (PARCIAL — sync+export ✅ / mapas ❌ pendientes)

- **Slice 7 — Sync + export (ADR-003):** ✅ **COMPLETA.** outbox en Room + WorkManager
  (`CONNECTED`, backoff exponencial, unique work `KEEP`) + firma HMAC V2
  (`X-Webhook-Signature-V2`) con secret en Android Keystore + dedupe por
  `ride.id` + data minimization + export GPX 1.1 con TrackPointExtension (U2, por SAF).
  **Queda pendiente solo el E2E contra un NAS con Hermes real.**
- **Slice 8 — Mapas (ADR-002):** ❌ **PENDIENTE (U5).** MapLibre 13.7.1 está declarado en
  `gradle/libs.versions.toml` con **cero usos de Kotlin**; MapLibre Native + OpenFreeMap online,
  atribución ODbL visible (`© OpenStreetMap contributors` + `© OpenMapTiles`)
  y User-Agent identificable de la app.
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

> **Estado real del schema (2026-10-30):** la fila «2 — Schema Room» habla de
> 6 tablas; en el repo solo existen **3** (`rides`, `track_points`, `outbox`,
> con migración Room 1→2). `bikes`/`components`/`component_events` se agregan en
> U4 (migración v1→v2).

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
