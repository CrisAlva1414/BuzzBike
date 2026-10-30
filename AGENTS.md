# AGENTS.md — BuzzBike

> Fuente de verdad del proyecto para agentes y colaboradores.
> Si este archivo contradice al código, el código manda — pero avisá y actualizá este archivo.

---

## 0. Estado actual y cómo retomar

> **Última actualización: 2026-10-30.**

**¿En qué quedó esto?** Fundación, scaffold, dominio, captura, descriptores,
elevación, calorías (nivel 3) y **backend/sync están cerrados**. El backend/sync
cerró con gate PASS y **264 tests**; `test`, `ktlintCheck`, `detekt`,
`assembleDebug` y `lintDebug` están en verde, y `assembleDebugAndroidTest`
compila. **La UI de producto está en curso.** CI/CD vive en
`.github/workflows/android.yml` (GitHub Actions): corre el gate, sube el artifact
`buzzbike-debug-apk` y publica un Release con el APK de debug en tags `v*`.

**Retomar (copiar y pegar):**

```bash
cd <ruta-del-repo>
./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain
./gradlew assembleDebugAndroidTest --console=plain   # compila instrumentados
```

Un slice se cierra con `tester: PASS` explícito. Si el gate falla, arreglar la
causa raíz antes de dar el slice por cerrado.

**Orden de lectura para un agente nuevo:**
`AGENTS.md` (este archivo) → `docs/roadmap.md` →
`docs/sessions/2026-10-26-sprint-01-toolchain-and-scaffold.md` →
`state/loop-state.md` (solo lectura) para el detalle fino.
**Si estás en una máquina nueva:** `docs/setup-entorno.md` va primero
(toolchain sin sudo, verificación y pitfalls).

**Reglas duras que no se negocian:**
1. Ningún slice se cierra sin `tester: PASS` (roadmap §4).
2. `security-reviewer` está **diferido al release** por decisión explícita del
   usuario — la deuda de seguridad está registrada (roadmap §4). La tarea no
   se marca completa sin ese pase.
3. Toda fórmula de dominio físico requiere fuente citada + ADR (§6).
4. La UI nunca muestra calorías como un número exacto: siempre intervalo de
   incertidumbre y modelo usado (§6, ADR-001).

**Pendientes que dependen del usuario:** app id definitivo (hoy
`com.buzzbike.app` es placeholder), nombre definitivo de la app, y validación
E2E del sync contra un NAS real con Hermes Agent (ruta de webhook). El
copyright ya está resuelto en el LICENSE (Cristian Alvarado, 2026).

---

## 1. Qué es BuzzBike

**BuzzBike** es una app Android open source (licencia MIT) de trackeo de rutas de bicicleta, orientada a ciclistas que quieren datos locales, auditable y sin dependencia de la nube.

### Qué hace (scope v1)
- Trackeo de rides con **GPS + sensores inerciales del teléfono** (acelerómetro, giroscopio, barómetro si existe).
- Cálculo de **descriptores de ride**: distancia, velocidad (media/pico), desnivel positivo/negativo, moving time.
- **Gasto calórico** con motor de cascada (ver ADR-001) y **UI que muestra la incertidumbre**, nunca un número presentado como exacto.
- **Tracking de desgaste de componentes** de la bici (cadena, suspensión, neumáticos, frenos) con alertas al 80% y 100% del ciclo de vida (ver ADR-004).
- Mapas **online** con MapLibre Native + OpenFreeMap (sin API key) (ver ADR-002).
- **Sync opcional** a Hermes Agent (framework de Nous Research) en un NAS del usuario, vía webhook HMAC sobre Tailscale (ver ADR-003).
- Exportación **GPX 1.1 + TrackPointExtension**.

### Qué NO es (scope boundaries explícitos)
- **No es una red social.** No hay feed, follows, kudos ni ranking social.
- **No es un reloj deportivo.** No compite con Garmin/Coros en precisión de sensores.
- **No usa HR strap ni potenciómetro en v1** — el usuario no los tiene. La arquitectura los permite enchufar después, pero el motor calórico funciona sin ellos (fallback MET).
- **No hay backend propio de BuzzBike.** No hay cuentas, ni servidores centrales, ni analytics de telemetría. El "servidor" opcional es el NAS del usuario.
- **No distribuye por Play Store.** Distribución vía GitHub Releases (APK). F-Droid posible a futuro.
- **No calcula calorías "exactas".** Ver ADR-001 (actualización 2026-10-27): sin perfil corporal la banda es ±30–40% y con perfil ±20–30% — bandas de producto, no intervalos de confianza. La UI debe mostrar rango, modelo e inputs usados.
- **No es un cliente genérico de Fitbit/Strava.** La integración con Hermes es vía webhook propio, no vía APIs de terceros.

---

## 2. Stack real y por qué

| Capa | Tecnología | Por qué |
|---|---|---|
| Lenguaje | Kotlin | Stack Android moderno, null-safety, coroutines. |
| UI | Jetpack Compose + Material 3 | UI declarativa, menos boilerplate que XML. |
| DI | Hilt | Estándar de facto para Android con Compose. |
| Persistencia | Room | SQLite con type-safe queries y migraciones. |
| Background | WorkManager | Sync diferido con constraints (CONNECTED) y backoff. |
| Mapas | MapLibre Native Android (v13.x, BSD 2-Clause) | Muy activo, sin vendor lock-in. Ver ADR-002. |
| Tiles | OpenFreeMap | Vector tiles, sin API key, sin límites de uso. Ver ADR-002. |
| BLE futuro | Nordic Android BLE Library 2.11.x (`no.nordicsemi.android:ble`) | Estándar para HR strap / CSC / power. En v1 solo queda el hook de arquitectura. |
| Foreground Service | types `health\|location` | Obligatorio en Android 14+ para trackeo continuo. |
| Target | Android 15/16 (API 35/36) | Target actual de distribución. |
| minSdk | 26 (Android 8) | Soporte de foreground services y BLE moderno. |
| Lint/format | ktlint + detekt | Linters Kotlin estándar. |

### Stack NO usado (y por qué — no "redescubrir")
- **osmdroid**: archivado nov 2024. Ver ADR-002.
- **tile.openstreetmap.org**: prohíbe offline/prefetch y bloquea por User-Agent. Ver ADR-002.
- **Google Maps / HERE / MapTiler**: ToS incompatibles con licencia MIT. Ver ADR-002.
- **ANT+**: requiere dongle USB; minoría de teléfonos. Descartado del camino crítico.
- **MediaPipe Pose en background**: requiere cámara + FGS `camera` + batería. No viable.
- **FIT como formato nativo**: licencia Garmin propietaria. Exportamos GPX.

---

## 3. Estructura de paquetes prevista

```
app/
└── src/main/java/com/buzzbike/app/   # TODO: confirmar app id (placeholder)
    ├── data/          # Repositorios, DAOs de Room, data sources, mappers.
    │                  # Único layer que conoce Room/JSON/red. Nada de UI ni de fórmulas.
    ├── domain/        # Modelos puros, casos de uso, y el motor de dominio físico
    │                  # (calorías, wear ratios, descriptores de ride).
    │                  # Sin dependencias de Android ni de Room. Testeable en JVM puro.
    ├── ui/            # Composables, ViewModels, navegación, estado de UI (sealed).
    ├── sensor/        # Adaptadores de sensores Android: GPS (FusedLocation),
    │                  # acelerómetro, giroscopio, barómetro. Emite Flow de lecturas.
    │                  # Capa de aislamiento: si mañana cambia el sensor API, solo cambia acá.
    └── sync/          # Outbox en Room, WorkManager, firma HMAC, client de Hermes.
                       # Nada de cálculo de dominio acá — solo transporte.
```

**Regla de dependencia:** `ui` → `domain` ← `data`; `sensor` y `sync` son edge adapters que hablan con `domain`/`data`, nunca al revés. `domain` no importa clases de Android.

---

## 4. Comandos de build / test / lint / format

> **Estado del entorno (2026-10-30):** toolchain **instalado**. JDK **Temurin 21.0.13**, Android SDK **37.0** en `~/android-sdk` (platform-tools, build-tools 36.0.0, licencias aceptadas), Gradle **9.8.2** vía wrapper. Matriz de versiones en `gradle/libs.versions.toml`.
>
> **Gate actual: verde.** `test` (**264 tests, 0 fallos**), `ktlintCheck`, `detekt`, `assembleDebug` y `lintDebug` en 0; `assembleDebugAndroidTest` compila.
>
> **Sin emulador ni dispositivo Android conectado** en esta máquina: `connectedDebugAndroidTest` **no se puede correr acá**. Todo lo verificable localmente es JVM: `test`, `ktlintCheck`, `detekt`, `lintDebug`. Los instrumentados solo se compilan con `assembleDebugAndroidTest`.
>
> **CI:** `.github/workflows/android.yml` corre el gate en cada push/PR con JDK 21 y SDK 37, sube el artifact `buzzbike-debug-apk` y publica un Release con `app-debug.apk` en tags `v*`. La release firmada de producción requiere keystore + secrets (pendiente).

```bash
# Build
./gradlew assembleDebug          # APK de debug
./gradlew assembleRelease        # APK de release (requiere keystore; sin él no se firma)
./gradlew bundleRelease          # AAB — NO se usa para distribución (sin Play Store)

# Test
./gradlew test                   # Unit tests (JVM) — incluye domain/
./gradlew assembleDebugAndroidTest   # Compila instrumentados (no los ejecuta)
./gradlew connectedDebugAndroidTest  # Ejecuta instrumentados (requiere emulador/dispositivo)

# Lint / static analysis
./gradlew detekt                # Análisis estático Kotlin
./gradlew lintDebug             # Android Lint (API/permisos/FGS)

# Format
./gradlew ktlintCheck           # Verificar formato
./gradlew ktlintFormat          # Aplicar formato

# Gate completo (correr antes de cada PR)
./gradlew test ktlintCheck detekt assembleDebug lintDebug
```

Config esperada: `ktlint` (formato oficial Kotlin) y `detekt` (reglas + complejidad). Detalle de estilo en `docs/format/estilo-kotlin.md`.

---

## 5. Reglas de commits

**Conventional Commits**, sin excepción:

```
<type>(<scope>): <description>
```

- **Tipos:** `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`
- **Scope:** opcional, entre paréntesis (ej. `feat(domain): ...`, `fix(sync): ...`)
- **Descripción:** en **inglés**, presente imperativo, **sin punto final**
- **Body:** solo el *por qué*, nunca el *qué* (eso ya lo dice el diff)

Ejemplos:

```
feat(domain): add calorie cascade with MET fallback

Implements the 3-level cascade from ADR-001. UI will surface
the uncertainty interval; no single "exact" number is exposed.

Closes #12
```

```
fix(sensor): cap accel sampling at 200Hz on Android 12+

Android 12+ enforces minDelay >= 5000us without
HIGH_SAMPLING_RATE_SENSORS. Cadence proxy does not need more.
```

Commiteá directo, sin pedir confirmación del mensaje.

---

## 6. Dominio físico — REGLA CRÍTICA

Las fórmulas de cálculo calórico y de desgaste **no son código trivial: son conocimiento científico con fuentes**. Este es el punto más importante de este archivo.

### Reglas inquebrantables
1. **Toda fórmula de dominio físico (calorías, BMR, MET, wear ratios) DEBE citar su fuente** — paper, estándar o compendium — en el código (KDoc) y en su ADR correspondiente.
2. **Todo cambio en esas fórmulas DEBE documentarse en `docs/adr/`** como un ADR nuevo o una actualización del existente, con la fuente citada. Un diff que cambie una constante fisiológica sin ADR se rechaza en review.
3. **Prohibido inventar papers, constantes ni URLs.** Si no hay fuente publicada, no se implementa — se marca `TODO:` y se discute.
4. **La UI nunca presenta un número calórico como exacto.** Debe mostrar: el intervalo de incertidumbre estimado y qué modelo de la cascada se usó. Ver ADR-001.
5. **El usuario no tiene HR strap ni potenciómetro en v1.** El motor debe funcionar con el nivel 3 de la cascada (Compendium por bandas de velocidad), pero la arquitectura debe permitir enchufar niveles 1 y 2 después sin reescribir el domain.

### Fuentes canónicas (v1 — no inventar otras)
| Fuente | Uso | Referencia |
|---|---|---|
| Keytel 2005 | Ecuación calorías con/sin VO2max (r=0.857 sin VO2max) | https://www.tandfonline.com/doi/full/10.1080/02640410470001730089 |
| Compendium of Physical Activities 2024 | METs por banda de velocidad | https://pacompendium.com/bicycling/ |
| ACSM Cycle METs | VO2 a partir de potencia (requiere potenciómetro) | https://exrx.net/Calculators/CycleMETs |
| Mifflin-St Jeor | BMR | ver ADR-001 |
| Wallen 2016 (PLOS One) | Reality check: error de wearables 9–43% | DOI: 10.1371/journal.pone.0154420 |

El detalle completo de la cascada está en **ADR-001**.

---

## 7. Privacy — REGLA CRÍTICA

Los tracks GPS son **datos de ubicación sensibles** bajo GDPR (y legislaciones equivalentes). BuzzBike se diseña **local-first**.

### Principios
1. **Nada sale del dispositivo sin acción explícita del usuario.** El sync a Hermes Agent es opcional, off por defecto, y el usuario lo activa ride a ride o de forma global sabiendo qué se envía.
2. **Data minimization en el sync.** Por defecto se envían métricas agregadas + waypoints decimados, **NO** el track GPS crudo de alta frecuencia. Ver ADR-003.
3. **Sin analytics de terceros, sin crash reporting que envíe PII, sin cuentas.**
4. **Los datos viven en Room (local).** Borrar la app borra todo. La exportación es GPX a storage del usuario.
5. **El secret HMAC va en Android Keystore**, nunca en plaintext ni en SharedPreferences sin cifrar. Ver ADR-003.
6. Si algún día se añade F-Droid, la build debe ser reproducible y sin telemetría.

---

## 8. Convenciones de código

- **Kotlin idiomático:** data classes para modelos, `when` exhaustivo, builders DSL donde aplique, sin `!!` salvo justificación documentada.
- **Coroutines + Flow** para streams de sensores y estado asíncrono. `suspend` en funciones de red/IO. **Nunca bloquear el main thread.**
- **Errores de dominio:** `Result<T>` de Kotlin o `sealed class` propio (ej. `CalorieCalcResult`). Exceptions solo para bugs, nunca para errores de negocio esperados (GPS sin fix, sensor ausente, etc.).
- **Estados de UI:** `sealed interface` (ej. `RideUiState`). Ver `docs/format/estilo-kotlin.md`.
- **Null safety:** preferir tipos no-null y `?` explícito. Evitar lateinit salvo inicialización garantizada.
- **Tests:** `domain/` debe ser testeable en JVM puro (sin Robolectric). Tests de fórmulas de calorías = snapshot de valores conocidos + cita a la fuente.
- **Naming:** Composables en PascalCase (`RideScreen`), funciones privadas en camelCase, constantes en SCREAMING_SNAKE.

---

## 9. Rama de trabajo

- Rama de integración: **`main`** (no hay `develop` en el remoto a 2026-10-02; si se crea, pasar a ella).
- `git pull` antes de empezar y antes de cualquier `push`.
- Commits directos en `main` en fase greenfield; PRs cuando el repo tenga colaboradores.

---

## 10. Documentación del proyecto

| Ruta | Qué hay |
|---|---|
| `AGENTS.md` | Este archivo. Fuente de verdad para agentes. |
| `README.md` | Intro del proyecto para humanos. Estado WIP. |
| `docs/adr/` | Architecture Decision Records. Fuente de verdad de por qué el sistema es así. |
| `docs/format/` | Convenciones de estilo Kotlin/Compose. |
| `docs/sessions/` | Bitácora de sesiones de trabajo. |
| `research/` | Índice de informes de investigación y sus fuentes. |
| `state/loop-state.md` | Bitácora del pipeline agéntico (no editar a mano; la actualiza el orchestrator). |

---

## 11. Pendientes abiertos (TODO)

- [ ] **Confirmar app id / package name.** Placeholder actual: `com.buzzbike.app`.
- [x] **Instalar JDK 21 + Android SDK 37** — **hecho el 2026-10-26 y actualizado el 2026-10-30.** JDK Temurin 21.0.13, Gradle 9.8.2 (wrapper del proyecto), Android SDK 37.0 en `~/android-sdk` (platform-tools, build-tools 36.0.0, licencias aceptadas). El gate completo está en verde (ver §4).
- [x] **Copyright holder del LICENSE** — resuelto: Cristian Alvarado, 2026.
- [ ] **Decidir nombre definitivo de la app** (BuzzBike es tentativo).
- [x] **CI/CD con GitHub Actions** — `.github/workflows/android.yml`: gate por push/PR, artifact `buzzbike-debug-apk` y Release de debug en tags `v*`.
- [ ] Future: HR strap BLE + potenciómetro (Nordic BLE Library ya elegida, sin implementar).
- [ ] Future: offline PMTiles (diferido, ver ADR-002).
- [ ] Future: F-Droid (requiere build reproducible, sin telemetría).
