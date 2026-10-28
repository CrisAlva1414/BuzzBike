# Setup de entorno — máquina Linux nueva (sin sudo)

> Guía copiar-y-pegar para compilar BuzzBike en una máquina Linux limpia,
> **sin privilegios de root**. Todo se instala bajo `$HOME`.
> Verificada contra la máquina de desarrollo original el 2026-10-26
> (JDK Zulu 17.0.13, Android SDK 36, Gradle 9.8.2 vía wrapper).
>
> Después de seguir esta guía, leé `AGENTS.md` §0 ("Estado actual y cómo retomar")
> antes de tocar código.

---

## 1. Qué se necesita y por qué

| Componente | Versión exacta | Por qué esta versión |
|---|---|---|
| JDK | **Zulu 17.0.13** (`zulu17.54.21-ca-jdk17.0.13-linux_x64`) | Android Gradle Plugin 9.4.1 requiere JDK 17+. Zulu = build OpenJDK LTS sin fricción de licencia; el CDN de Azul fue ~6× más rápido que el de Adoptium en la máquina original. |
| Android SDK | **platforms;android-36**, **build-tools;36.0.0**, **platform-tools** | `compileSdk`/`targetSdk` del proyecto = 36 (Android 15/16). Ver `gradle/libs.versions.toml` y `app/build.gradle.kts`. |
| Gradle | **9.8.2** (vía wrapper del proyecto) | Exigido por AGP 9.4.1. **No instalarlo a mano**: el wrapper (`./gradlew`) lo baja solo. |
| Android cmdline-tools | build **15859902** (verificado 2026-10-26; ver §3.2) | Solo para instalar el SDK vía `sdkmanager`. No hace falta Android Studio. |

La matriz de versiones completa (AGP, Kotlin, KSP, Hilt, Room, etc.) vive en
`gradle/libs.versions.toml` — **esa es la fuente de verdad**, no este doc.

## 2. Requisitos previos

- Linux x86_64 (probado en Ubuntu base / Zorin).
- `curl` y `unzip` (casi siempre preinstalados; si faltan, pedirle al admin o
  usar los `.zip` de cualquier mirror).
- **15–20 GB de disco libre**: el primer build descarga Gradle + todas las
  dependencias de Maven/Google y consume varios GB (en la máquina original se
  pasó de ~20 GB a ~13 GB libres tras instalar todo + primer build).
- Acceso a red (CDNs de Azul, Google y Gradle).
- **No se necesita root**, ni Android Studio, ni emulador.

## 3. Instalación paso a paso

Cada bloque es independiente y seguro de re-correr. Copialos en orden.

### 3.1. JDK Zulu 17

```bash
set -euo pipefail
mkdir -p "$HOME/tools"
cd "$HOME/tools"

# El nombre del archivo = nombre del directorio + .tar.gz.
# IMPORTANTE: antes de bajar, confirmá en https://www.azul.com/downloads/?version=java-17-lts
# que el release "zulu17.54.21-ca-jdk17.0.13-linux_x64" sigue publicado;
# Azul rota los builds y el número puede cambiar.
curl -fL -o jdk17.tar.gz \
  "https://cdn.azul.com/zulu/bin/zulu17.54.21-ca-jdk17.0.13-linux_x64.tar.gz"

tar -xzf jdk17.tar.gz
export JAVA_HOME="$HOME/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64"
"$JAVA_HOME/bin/java" -version
```

La última línea **debe** imprimir exactamente:

```
openjdk version "17.0.13" 2024-10-15 LTS
```

(Si imprimí otra cosa, no sigas: la matriz de versiones asume 17.0.13.)

**Alternativa Temurin/Adoptium:** si el CDN de Azul va lento o el release
ya no existe, el mismo resultado da Temurin 17 desde
<https://adoptium.net/temurin/releases/?version=17> (elegir `.tar.gz`,
linux x64, JDK). Después ajustá `JAVA_HOME` al directorio extraído
(`temurin-17*`) en el `androidenv.sh` del paso 3.6. No se mezclan JDKs.

### 3.2. Android command-line tools

```bash
set -euo pipefail
export JAVA_HOME="$HOME/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64"
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"

# El número <BUILD> se consulta SIEMPRE en la página oficial de Android Studio:
#   https://developer.android.com/studio#command-line-tools-only
# El build 15859902 fue el vigente y verificado el 2026-10-26.
# Google rota este número en cada release; re-verificalo antes de bajar.
BUILD=15859902
curl -fL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-${BUILD}_latest.zip"

unzip -q cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip
```

Esto deja `sdkmanager` en `$ANDROID_HOME/cmdline-tools/latest/bin/` — el
layout canónico que AGP espera (no moverlo de ahí).

### 3.3. Paquetes del SDK

```bash
set -euo pipefail
export JAVA_HOME="$HOME/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

### 3.4. Licencias

```bash
yes | sdkmanager --licenses
```

Tiene que terminar sin errores y dejar archivos en `$ANDROID_HOME/licenses/`.
Es obligatorio: sin esto, el build de Gradle se queja de "licencias no
aceptadas" y falla en una etapa tardía.

### 3.5. Script de entorno (`~/tools/androidenv.sh`)

Creá `~/tools/androidenv.sh` con **exactamente** este contenido:

```bash
export JAVA_HOME=$HOME/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
export GRADLE_USER_HOME=$HOME/.gradle
```

### 3.6. Cargarlo

```bash
source ~/tools/androidenv.sh
```

Cargá este script **en cada terminal** antes de correr Gradle (o agregá el
`source` a tu `~/.bashrc` / `~/.profile` si querés que quede permanente).

## 4. Verificación del entorno

Con el script cargado, estas 4 comprobaciones tienen que pasar:

```bash
source ~/tools/androidenv.sh
java -version | head -1
# → openjdk version "17.0.13" 2024-10-15 LTS

adb version | head -1
# → Android Debug Bridge version 1.0.41

ls "$ANDROID_HOME/platforms"
# → android-36

./gradlew --version   # desde la raíz del repo
# → Gradle 9.8.2
```

Si `./gradlew --version` muestra otra versión de Gradle, algo tocó el
wrapper: restaurá `gradle/wrapper/gradle-wrapper.properties` desde git.

## 5. Primer build

```bash
source ~/tools/androidenv.sh
cd <ruta-del-repo>
./gradlew assembleDebug --console=plain
```

**Expectativa honesta:** la primera corrida baja la distribución de Gradle
(~145 MB) + todas las dependencias (Compose, Hilt, Room, MapLibre…). En una
red lenta puede tardar **mucho** (minutos u horas). `--console=plain` hace
que se vea el progreso task por task en el log — sin eso, en pipes/logs no
ves nada hasta el final.

Cuando el gate de verificación esté verde, corré el resto (detalle en
`AGENTS.md` §0 y §4):

```bash
./gradlew test ktlintCheck detekt lint --console=plain
```

## 6. Pitfalls conocidos (ahorran horas)

- **Hilt y AGP son un par cerrado.** Hilt `2.60.x` exige AGP `>= 9.0.0`. Si
  alguien baja AGP a 8.x, el build **falla al aplicar el plugin** con un error
  explícito tipo *"The Hilt Android Gradle plugin is only compatible with AGP
  9.0.0 or higher (found 8.x.x)"*. No mezclar: o Camino A (AGP 9.x + Hilt
  2.60.x) o Camino B documentado en `gradle/libs.versions.toml` (AGP 8.x +
  Hilt ≤ 2.59.2). El proyecto vive en el Camino A.
- **AGP 9 no lleva el plugin `org.jetbrains.kotlin.android`.** Kotlin viene
  embebido en AGP; aplicar ese plugin es un **error duro**. El catálogo solo
  pinnea el plugin Compose (`kotlin-compose`).
- **KSP cambió de esquema en 2.3.0**: desde ahí usa **versionado
  independiente**. La última versión acoplada a Kotlin fue `2.2.21-2.0.5`.
  **No existe KSP 2.4.x** — no busques `2.4.21-*`, no existe. El proyecto usa
  el standalone `2.3.12`.
- **`local.properties` no se commitea** (está en `.gitignore`). Con
  `ANDROID_HOME` exportado vía `androidenv.sh` **no hace falta generarlo**: el
  AGP lo detecta del entorno.
- **Sin emulador ni dispositivo**: `connectedDebugAndroidTest` **no corre**
  (y no hay instrumented tests en el proyecto). Todo lo verificable en esta
  máquina es JVM: `test`, `ktlintCheck`, `detekt`, `lint`.
- **`build-tools/36.0.0.broken`**: en la máquina original quedó un directorio
  así, resto de una instalación fallida. Es **inofensivo**; el directorio
  funcional es `36.0.0`. Si lo ves en una máquina nueva, simplemente borrálo.

## 7. Si algo falla

1. **Gradle "colgado" o daemon en mal estado:**

   ```bash
   ./gradlew --stop
   ```

2. **Log completo legible.** Siempre con `--console=plain`; sin eso, en
   entornos sin TTY la salida se resume y se pierde el detalle del error.

3. **Último recurso — borrar la caché de dependencias** (fuerza a re-bajar
   todo; úsalo solo si el error es de artefactos corruptos o truncados, típico
   de red lenta interrumpida):

   ```bash
   rm -rf ~/.gradle/caches
   ```

4. **Contexto del wrapper:** `gradle/wrapper/gradle-wrapper.properties` fija
   `networkTimeout=10000` (10 s) y `retries=0`. En una red muy lenta, la
   descarga de la distribución puede cortarse por timeout **sin reintentar**.
   Si eso pasa, bajá el zip a mano
   (`https://services.gradle.org/distributions/gradle-9.8.2-bin.zip`) o
   reintentá: al reintentar, la descarga retoma donde quedó.

5. **Memoria del daemon:** `gradle.properties` define
   `org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m`. Si la máquina
   tiene poca RAM y ves OOM del Kotlin compile daemon, subí `-Xmx` ahí (no en
   variables de entorno sueltas).
