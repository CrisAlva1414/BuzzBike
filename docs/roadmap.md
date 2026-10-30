# Roadmap — BuzzBike

> Documento de trabajo, no marketing. Sirve para retomar el proyecto en cualquier
> momento sin releer todo el repo. Fuente de verdad del *por qué*: `AGENTS.md` y
> `docs/adr/`. Fuente de verdad del *estado real*: `AGENTS.md` §0 y `docs/sessions/`.
> Última actualización: **2026-10-30**.

---

## 1. Estado actual

> **Nota breve (2026-10-30).** El estado vigente del proyecto se mantiene en
> **`AGENTS.md` §0**. A la fecha: **U0 (tema/componentes), L1 (pause/resume + FGS)
> y U1 (Ride en vivo) están hechos**; el próximo slice es **U2 (historial/detalle
> + GPX)**. El resto de este documento queda como **referencia de fases y
> criterios**, no como estado real; si hay conflicto, manda `AGENTS.md` §0.

---

## 2. Fases y slices

> Reordenamiento de los 8 slices originales de la sesión de discovery
> (2026-10-02) en fases con dependencias explícitas. Cada slice es mergeable
> a `main` y debe dejar el repo compilable.

### Fase 0 — Fundación (COMPLETA)

- Investigación (3 informes en paralelo: sensores/calorías, Hermes, mapas/wear) → `research/`.
- Docs base: `AGENTS.md`, ADR-001 a ADR-004, `docs/format/`, `LICENSE`, `README.md`.
- Toolchain: JDK 17 (Zulu), Gradle 9.8.2 (wrapper), Android SDK 36.

### Fase 1 — Scaffold (EN CURSO)

**Slice 1 — build Gradle + Compose + Hilt + Room + ktlint/detekt.**
Archivos escritos y commiteados (`e54cce2`). **Pendiente: gate de verificación**
(`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `lint`) y arreglo de lo que falle.

### Fase 2 — Dominio y datos

- **Slice 2 — schema Room:** tablas `bikes`, `components`, `component_events`,
  `rides`, `ride_points`, `outbox` con `exportSchema = true`.
- **Slice 4 — motor de descriptores:** distancia, moving time, velocidad
  (media/pico), desnivel +/− con suavizado e histerésis (barómetro primario,
  GPS como fallback). Tests JVM puro con fixtures de tracks conocidos.

### Fase 3 — Captura

- **Slice 3 — Foreground Service** `health|location` + `FusedLocationProviderClient`
  (`PRIORITY_HIGH_ACCURACY`) + pipeline de puntos con batching + persistencia en Room.
  Activity Recognition (`ON_BICYCLE`) como gate de sesión.
- **Slice 3b (opcional) — sensores inerciales:** acelerómetro/giroscopio (cap 200 Hz
  en Android 12+ sin `HIGH_SAMPLING_RATE_SENSORS`) y barómetro si existe.
  Detección de movimiento, **no** medición de esfuerzo fisiológico.

### Fase 4 — Cálculo

- **Slice 5 — `CaloriesEngine` (ADR-001):** cascada ACSM → Keytel → Compendium MET.
  En v1 solo corre el **nivel 3** (Compendium por bandas de velocidad GPS); los
  niveles 1 y 2 quedan como hooks. BMR Mifflin-St Jeor. **UI de incertidumbre
  obligatoria:** intervalo + modelo usado, nunca un número exacto solo.
- **Slice 6 — `WearEngine` (ADR-004):**
  `wearRatio = max(km/lifecycleKm, hours/lifecycleHours, days/lifecycleDays)`.
  Alertas `approaching` (80%) y `due` (100%). Evento `service` con snapshot previo
  en `component_events`. Defaults: cadena 2000 km, suspensión 100 h, neumáticos
  3000–5000 km, frenos por km + desnivel negativo (proxy, ponderación = TODO).

### Fase 5 — Salida

- **Slice 7 — Sync + export (ADR-003):** outbox en Room + WorkManager
  (`CONNECTED`, backoff exponencial, unique work `KEEP`) + firma HMAC V2
  (`X-Webhook-Signature-V2`) con secret en Android Keystore + dedupe por
  `ride.id` + data minimization + export GPX 1.1 con TrackPointExtension.
- **Slice 8 — Mapas (ADR-002):** MapLibre Native + OpenFreeMap online,
  atribución ODbL visible (`© OpenStreetMap contributors` + `© OpenMapTiles`)
  y User-Agent identificable de la app.
- **CI + release workflow** en GitHub (lint + unit tests en JVM; APK de release).

### Fase 6 — Release gate

- `security-reviewer` completo sobre **todo el diff acumulado** (ver §4).
- Lint/format final, tag, GitHub Release con APK firmado.

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

---

## 4. Decisiones de proceso

1. **Gate por slice = `builder` + `tester`.** `builder` implementa; `tester` valida
   y emite PASS/FAIL explícito. Un slice sin PASS no se da por cerrado.
2. **`security-reviewer` diferido — decisión explícita del usuario (2026-10-26).**
   En vez de correr seguridad por slice, se corre **un único pase completo sobre
   todo el diff acumulado** como **gate de release** (fase 6). Racional: acelerar
   el desarrollo en greenfield.
3. **Riesgo aceptado y registrado.** El slice 7 (secret HMAC + Keystore + red)
   acumula deuda de seguridad durante varias iteraciones hasta el gate final.
   **Cláusula de escape:** si en cualquier momento aparece un hallazgo de
   **severidad crítica** en ese slice, se revisa **en el momento**, sin esperar
   al release.
4. **Regla que NO se relaja (AGENTS.md §6):** ningún cambio en fórmulas de
   dominio físico (calorías, BMR, MET, wear) se acepta sin fuente citada en KDoc
   y ADR nuevo o actualizado. Aplica aunque el security review esté diferido.

---

## 5. Restricciones y supuestos

- **Sin sensores externos en v1** (sin HR strap, sin potenciómetro) → techo de
  precisión calórica **±30–40% sin perfil / ±20–30% con perfil (ADR-001, bandas
  de producto)**. **No prometer exactitud**; la UI declara el intervalo y el modelo.
- **Distribución: GitHub Releases (APK).** Sin Play Store. F-Droid solo futuro
  con build reproducible.
- **Hermes Agent en el NAS del usuario**, alcanzable por Tailscale. Si no está
  listo al llegar al slice 7, se implementa contra la spec de ADR-003 y se
  valida con un mock receiver local.
- **Equipo: una persona + pipeline agéntico.** Sin emulador ni dispositivo
  Android conectado a esta máquina → **no hay instrumented tests**; todo lo
  verificable en CI/máquina es JVM (`test`, `ktlintCheck`, `detekt`, `lint`).
- **Entorno sin sudo**, instalación manual del toolchain, red lenta hacia el
  CDN de Google (usar el de Azul para JDK si hay que reinstalar).
- **App id `com.buzzbike.app` y nombre "BuzzBike" son placeholders** hasta
  confirmación (AGENTS.md §11).
