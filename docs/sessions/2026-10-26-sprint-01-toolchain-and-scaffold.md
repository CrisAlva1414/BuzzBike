# Sesión 2026-10-26 — Sprint 01: Toolchain + Scaffold

- **Fecha:** 2026-10-26
- **Tipo:** Implementación (slice 1 — scaffold del proyecto Android)
- **Estado al cerrar:** Toolchain instalado y scaffold escrito y commiteado. **Slice 1 sin cerrar:** el gate de verificación (`assembleDebug` + `test` + `ktlintCheck` + `detekt` + `lint`) no se completó.

---

## Resumen

Sesión retomada tras un reinicio de la PC, con el objetivo declarado por el usuario de **acelerar el proceso**: `builder` + `tester` por slice, y `security-reviewer` corrido una sola vez al final como **gate de release** (decisión documentada en `docs/roadmap.md` §4). Trabajo de la sesión: verificar qué sobrevivió del reinicio, instalar el toolchain que no existía al inicio del proyecto, resolver empíricamente una matriz de versiones inválida, revisar prior art (`biketrackd`), y escribir el scaffold del slice 1. **No se validó el build**: `assembleDebug` fue interrumpido a pedido del usuario y los otros 4 comandos del gate nunca se corrieron.

---

## 1. Verificación del entorno tras el reinicio

Se confirmó que sobrevivieron al reinicio: **JDK, Android SDK, Gradle** y el **Gradle wrapper** del proyecto (que se había generado justo antes del corte de la sesión anterior).

## 2. Toolchain instalado

Al inicio del proyecto **no había nada**: ni JDK ni Android SDK (estado documentado el 2026-10-02). Se instaló todo manualmente, sin sudo:

| Componente | Detalle |
|---|---|
| JDK | **Zulu 17.0.13** en `/home/notebook01/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64` |
| Gradle | **9.8.2** y **8.14.5** en `/home/notebook01/tools/` (el proyecto usa 9.8.2 vía wrapper) |
| Android SDK | **36** en `~/Android/Sdk` — platform-tools **37.0.1**, `platforms;android-36`, `build-tools;36.0.0`, licencias aceptadas |
| Script de entorno | `/home/notebook01/tools/androidenv.sh` (exporta `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`, `GRADLE_USER_HOME`) |

**Restricciones del entorno que condicionaron la instalación:**

- **Sin sudo** → instalación manual bajo `~/tools/` y `~/Android/Sdk`.
- **Red lenta** hacia el CDN de Google (~280 KB/s). El JDK se bajó del CDN de Azul, que iba ~6× más rápido.
- **Disco:** de ~20 GB libres a **~13 GB** tras instalar todo.

Nota menor: en `~/Android/Sdk/build-tools/` quedó un directorio `36.0.0.broken` (vestigio de una descarga fallida); el directorio funcional es `36.0.0`.

## 3. Resolución empírica de la matriz de versiones

**Bug encontrado:** la matriz de versiones inicial del proyecto era **inválida**. `Hilt 2.60.1` exige `AGP >= 9.0.0`, pero el proyecto declaraba `AGP 8.13.2`. Error real observado:

> `The Hilt Android Gradle plugin is only compatible with Android Gradle plugin (AGP) version 9.0.0 or higher (found 8.13.2)`

Solo dos mezclas son válidas: **Camino A** (toolchain actual stable) o **Camino B** (AGP 8.13.2 + Hilt ≤ 2.59.2, forzando Kotlin 2.2.21). Se eligió el **Camino A**:

| Componente | Versión |
|---|---|
| AGP | 9.4.1 |
| Gradle | 9.8.2 |
| Kotlin | 2.4.21 — **solo** el plugin Compose; AGP 9 trae Kotlin embebido |
| KSP | 2.3.12 (independiente) |
| Hilt | 2.60.1 |
| Room | 2.8.5 |
| Compose BOM | 2026.09.00 |
| lifecycle | 2.11.0 |
| activity-compose | 1.13.0 |
| navigation-compose | 2.10.2 |
| hilt-navigation-compose | 1.4.0 |
| MapLibre | 13.7.1 |
| ktlint (plugin jlleitschuh) | 14.2.0 |
| detekt | 1.23.8 |

**Hallazgos técnicos a recordar (documentados también en `gradle/libs.versions.toml`):**

- KSP cambió a **versionado independiente en 2.3.0**: ya no existe KSP 2.4.x ni el scheme acoplado `"kotlinVersion-kspVersion"` (la última versión acoplada fue `2.2.21-2.0.5`). Se usa el standalone `2.3.12`; su compatibilidad con Kotlin 2.4.21 **se valida en cada build del gate** (todavía sin cerrar).
- Con AGP 9, aplicar el plugin `org.jetbrains.kotlin.android` es un **error duro** — Kotlin viene embebido en AGP; el catálogo solo pinnea el plugin Compose.

## 4. Investigación de prior art (`biketrackd` y otros)

Veredicto: **no forkear, solo referencia.** Motivos:

- Su wear es **km-only**, sin eventos de service ni reset — no cumple ADR-004.
- No tiene calorías ni sync.
- Tiene la **atribución ODbL deshabilitada** (issue #1 abierta en su repo) — riesgo legal que no se hereda.

Sí vale copiar (con atribución, es MIT): el patrón **OpenFreeMap + OfflineManager** y el concepto de **badges**.

## 5. Scaffold del slice 1 escrito

Se escribieron los archivos del scaffold (lista completa en la sección siguiente). Incluye: build Gradle (raíz y módulo `app`), version catalog, wrapper, `.editorconfig`, config de detekt, `AndroidManifest.xml`, `BuzzBikeApplication` + `MainActivity`, theme Compose, placeholders de las capas `data/`/`domain/`/`sensor/`/`sync/`, resources, y un unit test de ejemplo (`ClampPercentTest`).

---

## Estado exacto al cerrar la sesión — LEER ESTO PARA RETOMAR

**El slice 1 NO está cerrado.** `./gradlew assembleDebug` se ejecutó y llegó hasta `:app:compileDebugKotlin`, donde **fue interrumpido a pedido del usuario**. No hay `BUILD SUCCESSFUL` ni `EXIT=` en el log, **no se produjo APK** (`app/build/outputs/apk/debug/` no existe).

**Evidencia** (`/home/notebook01/tools/build1.log`, 2026-10-26 09:16): el log termina exactamente en:

```
> Task :app:kspDebugKotlin
> Task :app:mergeExtDexDebug
> Task :app:mergeLibDexDebug
> Task :app:compileDebugKotlin
```

sin línea de resultado final. Nota: `kspDebugKotlin` llegó a ejecutarse en esa corrida, lo que indica que KSP 2.3.12 aplicó sobre Kotlin 2.4.21 al menos hasta esa etapa — **pero esto no es un PASS del gate**.

### Estado de git (VERIFICADO 2026-10-26 al redactar esta bitácora)

> **Corrección respecto del brief de esta sesión:** al cierre de la sesión de trabajo
> el scaffold quedó **commiteado**, no untracked. Verificado con `git log`:

| Commit | Fecha | Mensaje |
|---|---|---|
| `c7eddea` | 2026-10-06 | `docs: add project foundation and architecture decisions` |
| `820fd53` | 2026-10-26 11:37 | `chore: harden .gitignore and untrack docs/config/internal files` (transitorio: sacó del tracking AGENTS.md/README/docs/research/state) |
| `e54cce2` (HEAD) | 2026-10-26 11:46 | `chore: add full project scaffold (docs/config included)` — re-agrega todo lo anterior **más el scaffold completo** |

Working tree **limpio**. Rama `main`. Sin remote configurado. El mensaje sugerido `chore(build): scaffold Gradle, Compose, Hilt and Room` **no se usó**; el mensaje real fue el de `e54cce2`.

### Verificado / No verificado

| Comando | Estado |
|---|---|
| `./gradlew assembleDebug` | ❌ **INCOMPLETO** — interrumpido en `:app:compileDebugKotlin`, sin APK |
| `./gradlew test` | ⚠️ **NUNCA ejecutado** |
| `./gradlew ktlintCheck` | ⚠️ **NUNCA ejecutado** |
| `./gradlew detekt` | ⚠️ **NUNCA ejecutado** |
| `./gradlew lint` | ⚠️ **NUNCA ejecutado** |

Para retomar:

```bash
source /home/notebook01/tools/androidenv.sh
cd /home/notebook01/Proyectos/BuzzBike
./gradlew assembleDebug
./gradlew test
./gradlew ktlintCheck
./gradlew detekt
./gradlew lint
```

### Archivos del scaffold (commiteados en `e54cce2`)

`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.editorconfig`, `.gitignore`, `config/detekt/detekt.yml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/buzzbike/app/BuzzBikeApplication.kt`, `app/src/main/java/com/buzzbike/app/MainActivity.kt`, `app/src/main/java/com/buzzbike/app/data/DataLayer.kt`, `app/src/main/java/com/buzzbike/app/domain/Percent.kt`, `app/src/main/java/com/buzzbike/app/sensor/SensorLayer.kt`, `app/src/main/java/com/buzzbike/app/sync/SyncLayer.kt`, `app/src/main/java/com/buzzbike/app/ui/theme/{Color,Theme,Type}.kt`, `app/src/main/res/**` (incluye `network_security_config.xml` y `data_extraction_rules.xml`), `app/src/test/java/com/buzzbike/app/domain/ClampPercentTest.kt`.

---

## Próximo paso concreto

1. Correr los 5 comandos del gate (arriba) y arreglar lo que falle. Candidatos probables de falla: incompatibilidades del **DSL por AGP 9**, o **KSP 2.3.12 vs Kotlin 2.4.21** (compatibilidad no demostrada todavía).
2. Si el gate queda verde, el slice 1 se da por cerrado con `tester: PASS`. **No re-commitear el scaffold** — ya está en `e54cce2`; solo commitear arreglos si los hay.

---

## Decisiones tomadas

- **Camino A de versiones** (AGP 9.4.1 + Gradle 9.8.2 + Kotlin 2.4.21 embebido + KSP 2.3.12 + Hilt 2.60.1), documentado en `gradle/libs.versions.toml`.
- **`biketrackd` = referencia, no base** (no forkear).
- **Gate por slice = `builder` + `tester`; `security-reviewer` diferido al release** como gate único — decisión explícita del usuario (2026-10-26). Riesgo registrado en `docs/roadmap.md` §4: si aparece un hallazgo crítico en el slice 7 (secret HMAC + Keystore + red), se revisa en el momento.
- **Sin sensores externos → precisión calórica ±25–35%**, declarada en la UI (ADR-001). Regla intacta: sin fuente citada + ADR no se toca fórmula de dominio físico.

## Pendientes abiertos

- [ ] **Cerrar el gate del slice 1** (5 comandos) — bloqueante para todo lo demás.
- [ ] App id definitivo (`com.buzzbike.app` es placeholder).
- [ ] Copyright holder del LICENSE (`[TU NOMBRE]`).
- [ ] Nombre definitivo de la app (BuzzBike es tentativo).
- [ ] Confirmar si el usuario tiene el **Hermes Agent** configurado con una ruta de webhook, para el slice 7 (si no, mock receiver local, ver ADR-003).
- [ ] URL del remote de GitHub (hoy no hay remote configurado).

## Notas para futuras sesiones

- El `state/loop-state.md` es bitácora del pipeline agéntico y **no se edita a mano**.
- Roadmap vivo con fases, criterios de aceptación y decisiones de proceso: `docs/roadmap.md` (creado 2026-10-26).
- Fórmulas de calorías/wear: cualquier cambio exige fuente citada + ADR (AGENTS.md §6). La UI **nunca** presenta un número calórico como exacto.
- Privacy: nada sale del dispositivo sin acción explícita del usuario; data minimization en el sync por defecto (ADR-003).
