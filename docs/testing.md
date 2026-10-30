# Estrategia de testing — BuzzBike

> Estado a 2026-10-28. Este documento describe qué se verifica hoy, qué
> queda como deuda de instrumentación y cómo correr cada cosa.

## 1. Qué se testea en JVM (`app/src/test`)

Todo lo que sigue corre sin emulador ni dispositivo, con
`./gradlew test`. Es la mayor parte de la confianza del proyecto.

| Área | Qué cubre | Runner |
|---|---|---|
| Dominio físico | Motor calórico (cascada ADR-001, bandas de incertidumbre), procesamiento de elevación/desnivel, agregación de ride, estimación de moving time y limpieza de trazas GPS. | JUnit puro |
| Sesión de ride | `TrackingManager` y `RideSessionManager` con sensores, permisos y persistencia fakeados. | JUnit + coroutines-test |
| Persistencia Room | DAOs, `RoomOutboxEnqueuer`/`RoomOutboxStore` y migraciones sobre una base in-memory. | Robolectric |
| Firma HMAC | Vector HMAC-SHA256 conocido y verificación de la firma enviada. | JUnit puro (`javax.crypto`) |
| Cliente HTTP / sync | `HttpHermesSender` contra un `HttpsServer` local efímero, códigos de respuesta, timeouts, validación de endpoint, `HttpConnectivityChecker`. | JUnit (`com.sun.net.httpserver`) |
| Payload | Serialización JSON del ride, inclusión del rango calórico y del modelo, contrato de campos. | JUnit puro |

> **Keystore TLS de test:** `app/src/test/resources/test-tls.p12` es un
> keystore **autofirmado y solo de test**; su password (`testpass`) es
> pública y no es un secreto de producción. Está versionado a propósito
> (excepción en `.gitignore`) para que el CI pueda levantar el `HttpsServer`
> efímero de los tests de `HttpHermesSender`.

Reporte HTML: `app/build/reports/tests/testDebugUnitTest/index.html`.

## 2. Qué requiere dispositivo o emulador (`app/src/androidTest`)

El source set instrumentado ya existe, pero **no hay emulador en esta
máquina** (ver §6): los tests compilan localmente y quedan como deuda de
hardware hasta que se ejecuten en CI con emulador o en un dispositivo
físico. Además de los dos tests nuevos de §3, los siguientes
comportamientos solo se pueden validar con hardware/emulador real:

- **SQLCipher real:** apertura, cifrado en reposo y `PRAGMA key` sobre una
  base física. Robolectric usa una implementación in-memory que no ejercita
  la librería nativa.
- **Android Keystore:** generación/uso de llaves AES y HMAC, sellado del
  secreto HMAC (`AndroidKeystoreSecretSealer`) y persistencia del
  passphrase de la base.
- **WorkManager / `HermesWorker`:** encolado periódico, constraints de red,
  backoff y ejecución real en background.
- **`TrackingService` (foreground service):** tipos `location`/`health`,
  notificación persistente, ciclo de vida y permisos runtime en Android
  14+ (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_LOCATION`,
  `ACCESS_FINE_LOCATION`).
- **`HttpsServer` real con Tailscale:** conectividad al NAS por MagicDNS /
  `100.64.0.0/10`, certificados y comportamiento de red real.

## 3. Tests instrumentados

Dos tests cubren los puntos que Robolectric no puede ejercitar: la
librería nativa de SQLCipher y Android Keystore.

### `EncryptedDatabaseInstrumentedTest`

`app/src/androidTest/java/com/buzzbike/app/data/local/EncryptedDatabaseInstrumentedTest.kt`

- Abre Room con SQLCipher (`SupportOpenHelperFactory` + passphrase sellada
  por `AndroidKeystoreSecretSealer`/`DatabasePassphraseProvider`).
- Inserta un `RideEntity` real, lo lee por `RideDao` y verifica que el `id`
  coincide.
- Cierra la base, lee los primeros 16 bytes del archivo y verifica que
  **no** empiezan con `SQLite format 3`, es decir, que no quedó texto plano.
- `@After` cierra la base y borra `instrumented-encrypted.db`.

### `KeystoreHmacInstrumentedTest`

`app/src/androidTest/java/com/buzzbike/app/sync/KeystoreHmacInstrumentedTest.kt`

- Importa un secreto de 32 bytes con `HmacSecretManager` y espera
  `ImportResult.Success` (queda sellado con AES-GCM de Android Keystore).
- Firma con `StoredHmacKeyProvider` + `HmacSha256Signer` y verifica la firma.
- Un payload alterado debe fallar la verificación.
- La firma hex tiene 64 caracteres (32 bytes de HMAC-SHA256) y el secreto
  nunca se loguea.

### Comandos

```bash
# Ejecutar todos los tests instrumentados (requiere emulador/dispositivo)
./gradlew connectedDebugAndroidTest

# Solo compilar los tests instrumentados (no requiere dispositivo)
./gradlew assembleDebugAndroidTest
```

**No hay emulador ni dispositivo en esta máquina**, así que
`connectedDebugAndroidTest` no se puede correr localmente. Que
`assembleDebugAndroidTest` compile es el gate actual; ejecutar los tests
es la deuda de hardware pre-release (ver §6).

## 4. Comandos

```bash
# Tests JVM (dominio, Room/Robolectric, HMAC, HTTP local, payload)
./gradlew test --rerun-tasks

# Solo un test/clase
./gradlew test --tests "com.buzzbike.app.domain.calories.*"

# Tests instrumentados (requiere emulador/dispositivo conectado)
./gradlew connectedDebugAndroidTest

# Instalar el APK debug en un dispositivo ya conectado
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Ver dispositivos disponibles
adb devices
```

Si en una máquina nueva el SDK/JDK no están en el `PATH`, cargar las
variables del entorno antes de Gradle (ver `docs/setup-entorno.md`).

## 5. Dependencias fijadas por seguridad

`app/build.gradle.kts` fuerza `org.bouncycastle:bcprov-jdk18on:1.84`
(CVE-2026-5598). El archivo solo entra al classpath de tests vía
Robolectric; no se empaqueta en la app. Verificar con:

```bash
./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath | grep bouncycastle
# Debe mostrar 1.84 (forzado), no 1.81.
```

## 6. Riesgo aceptado

No hay emulador ni dispositivo Android en esta máquina. Por eso:

- `connectedDebugAndroidTest` no se puede ejecutar localmente.
- Los puntos de §2 quedan como **deuda pre-release**: deben cubrirse antes
  de publicar una release, idealmente en CI con un emulador headless o en
  un dispositivo físico.
- El resto del pipeline (compilación, `test`, `ktlintCheck`, `detekt`,
  `lintDebug`, `assembleDebug`) sí se valida en JVM y es el gate actual.

## 7. Dependency locking

`app/build.gradle.kts` habilita `dependencyLocking { lockAllConfigurations() }`
y el repo versiona `app/gradle.lockfile` (más `settings-gradle.lockfile`
para el catálogo de versiones). Regenerar tras cambiar dependencias:

```bash
./gradlew :app:dependencies --write-locks
```

Si un cambio de versión falla por el lockfile, actualizarlo con el comando
anterior y revisar el diff antes de commitear.
