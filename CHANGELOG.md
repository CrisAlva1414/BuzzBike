# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto adhiere a [Versionado Semántico](https://semver.org/lang/es/).

## [1.0.0] - 2026-11-01

Primera release consolidada del producto completo: fundación (dominio
físico, captura GPS, sync HMAC) más las unidades de producto U1–U6.
Local-first, MIT, sin backend propio ni telemetría.

### Añadido

- **Ciclo completo de ride:** captura GPS con foreground service, pantalla
  en vivo con estados sellados, pausa/terminar, resumen y recuperación de un
  ride interrumpido; permiso de ubicación en runtime con acción «ABRIR
  AJUSTES» en denegación permanente (U1 / U1.1).
- **Descriptores de ride** (distancia, moving time, velocidades, desnivel
  +/−) y **motor de calorías nivel 3** (Compendium 2024) con banda de
  incertidumbre y modelo siempre visibles, nunca un número exacto
  (ADR-001, ADR-005, ADR-006).
- **Historial y detalle:** lista de rides con franja semanal, detalle con
  8 métricas y pestañas **DATOS/MAPA**; **exportación GPX 1.1 +
  `TrackPointExtension`** a destino elegido por el usuario vía SAF (U2).
- **Perfil corporal** (peso, altura, edad, sexo) en **Room cifrado con
  SQLCipher**, con snapshot por ride, `hasProfile` y Mifflin-St Jeor como
  baseline de paradas (U3, ADR-007).
- **Garaje y desgaste:** bicis y componentes con motor de desgaste por
  máximos normalizados, alertas al **80%** y **100%**, historial de
  servicios y bici asignada al ride (U4, ADR-004).
- **Mapa del recorrido** con MapLibre + OpenFreeMap sin API key, atribución
  visible permanente y aviso de offline con reintentar (U5, ADR-002); el
  track se renderiza en local y sus coordenadas no se envían.
- **Sync opcional** a tu propio Hermes/NAS: webhook firmado **HMAC-SHA256**
  sobre **HTTPS**, outbox en Room, WorkManager con backoff, data
  minimization (métricas agregadas + waypoints decimados, no track crudo)
  y dedupe idempotente por `ride.id` (ADR-003); secreto en Android Keystore.
- **Persistencia cifrada** (Room + SQLCipher, passphrase en Keystore),
  tema Sol/Night y CI con GitHub Actions (gate + artifact + release en tags).

### Cambiado

- Barra de acción del ride y tabs DATOS/MAPA **fijas** fuera del scroll
  (visibles en 320 dp); métricas con semántica TalkBack sin dobles
  lecturas; `liveRegion` en el aviso del mapa (U6).
- Borrar una bici deja los rides con su `bike_id` como **referencia
  histórica suave** (detalle: «Bici eliminada»); componentes y eventos se
  borran por CASCADE.
- Un perfil **parcial** degrada a la banda sin perfil (±30–40%): no se
  inventa un estado intermedio; sin peso se etiqueta «supuesto: 70 kg».
- Contrato de sync en **payload v2**: la banda calórica y el modelo van
  embebidos (breaking change respecto del escalar `caloriesKcal` v1).

### Corregido

- La instalación limpia dejaba «INICIAR RIDE» deshabilitado porque nadie
  solicitaba el permiso de ubicación.
- `runCatching` tragaba `CancellationException` en ViewModels y exportador
  GPX; ahora la cancelación se propaga (`runCatchingCancellable`).
- `RideFormat` degrada a `—` con NaN, infinitos o negativos, y formatea
  tiempo con `Locale.ROOT`.

### Seguridad

- Releases **sin APKs de debug**; el job de release es fail-closed con los
  5 secrets, publica checksum **SHA-256** y rechaza `android:debuggable` y
  certificados `CN=Android Debug`.
- **HTTPS-only** (`cleartextTrafficPermitted=false`), HMAC en cada envío,
  secretos sellados con AAD de propósito, SQLCipher 4.19.1 y lockfile de
  dependencias.
- **Gate de mutación de sync** (revocación por epoch) que cierra la carrera
  save/disconnect entre pantallas y con el ride.
- `testConnection` descarta resultados obsoletos por generación; sin
  analytics, sin crash reporting con PII, sin cuentas.

---

## [No publicado]

### Añadido

### Cambiado

### Corregido

### Seguridad

---

## [0.1.0] - 2026-10-30

Primer APK de debug instalable, publicado como GitHub Release
([`v0.1.0`](https://github.com/CrisAlva1414/BuzzBike/releases/tag/v0.1.0)).

### Añadido

- **Dominio de rides:** descriptores (distancia, moving time, velocidad
  media/máxima, desnivel +/−), segmentación de traza por pausas (ADR-005) y
  elevación con fusión barómetro + altitud GPS, suavizado e histéresis (ADR-006).
- **Motor de calorías nivel 3** (Compendium 2024, METs por banda de velocidad)
  con BMR Mifflin-St Jeor, banda de incertidumbre y modelo expuesto a la UI,
  nunca un número exacto (ADR-001). ACSM y Keytel quedan como hooks no
  implementados.
- **Captura GPS** con `FusedLocationProviderClient` y pipeline de puntos
  persistidos en Room; **foreground service de ubicación** con control de
  pausa/reanudación.
- **Persistencia local cifrada:** Room + SQLCipher con tablas `rides`,
  `track_points` y `outbox`, más sus migraciones.
- **Sync opcional a Hermes Agent / webhooks firmados** (ADR-003): outbox en
  Room + WorkManager con constraint de red y backoff exponencial, firma
  `X-Webhook-Signature-V2` (HMAC_SHA256) con secret en Android Keystore,
  dedupe idempotente por `ride.id`, data minimization (métricas agregadas +
  waypoints decimados), health check estricto y pantalla de ajustes de sync.
- **Tema Sol/Night** y componentes base de UI.
- **CI con GitHub Actions:** gate `test ktlintCheck detekt lintDebug
  assembleDebug` en cada push/PR, artifact `buzzbike-debug-apk` y publicación
  automática de Release en tags `v*`.

### Notas de release

- `versionName 0.1.0` / `versionCode 1`; `applicationId com.buzzbike.app`
  (placeholder) — app id y nombre definitivo siguen pendientes.
- APK de **debug** de 84.5 MB firmado con la clave de debug: sirve para probar,
  no para distribución de producción. La release firmada requiere keystore y
  secrets (pendiente).
- El E2E del sync contra un NAS con Hermes Agent real **no está validado**; los
  tests cubren la spec contra mock.
