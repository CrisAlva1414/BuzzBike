# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto adhiere a [Versionado Semántico](https://semver.org/lang/es/).

## [No publicado]

### Añadido

- Pantalla de ride en vivo con estados de reposo, listo, grabando, pausado,
  terminado y recuperable.
- Resumen del ride al terminar, con distancia, velocidad, duración, desnivel y
  banda calórica.
- Recuperación de un ride interrumpido: al reabrir la app se puede salvar o
  descartar sin perder los datos capturados.
- Métricas en vivo con banda de incertidumbre calórica y modelo aplicado
  (ADR-001).
- Solicitud del permiso de ubicación en runtime desde la pantalla de ride, con
  acciones y mensajes para permiso denegado y ubicación aproximada.
- Historial de rides con lista ordenada y franja semanal (distancia y cantidad
  de rides por semana calendario).
- Detalle del ride con su traza y 8 métricas; la banda calórica solo se muestra
  si el modelo y los extremos están completos (nunca se rellena con valores
  inventados).
- Exportación a GPX 1.1 con `TrackPointExtension` a un destino elegido por el
  usuario vía Storage Access Framework (destinos `content://`, sin permisos de
  almacenamiento).

### Cambiado

- La sesión de ride usa un contrato sellado que serializa todas las
  transiciones y evita estados inválidos.
- La auto-pausa se deriva de la detección de movimiento existente (ADR-005),
  sin umbrales nuevos.

### Corregido

- La instalación limpia dejaba «INICIAR RIDE» deshabilitado para siempre
  porque nadie solicitaba el permiso de ubicación.

### Seguridad

- Los releases ya no publican APKs debug; el job de release exige firma y
  publica checksum SHA-256. SQLCipher actualizado a 4.19.1. Secretos sellados
  con AAD de propósito.
- El job de release exige `RELEASE_CERT_SHA256` y rechaza APKs con
  `android:debuggable` o certificados `CN=Android Debug`.
- Gate de mutación de sync compartido (`SyncMutationGate` + `SyncGatekeeper`)
  con revocación por epoch: cierra la carrera save/disconnect entre pantallas
  y con el ride.
- Lockfile de dependencias para builds reproducibles.
- `testConnection` descarta resultados obsoletos por generación (evita
  aplicar respuestas viejas del health check).

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
