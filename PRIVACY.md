# Privacidad — BuzzBike

**Última actualización: 2026-11-01.**

BuzzBike es **local-first**: no hay backend de BuzzBike, no hay cuentas y no
hay telemetría. Este documento describe qué datos se guardan, qué sale del
dispositivo y cómo borrarlo. Si algo de este archivo contradice al código,
el código manda — pero avisá en un issue.

---

## Qué datos se guardan y dónde

Casi todos los datos se guardan **en el propio dispositivo**, en una base de
**Room cifrada con SQLCipher**. La passphrase de la base está **sellada en
Android Keystore** (AES-GCM, hardware-backed cuando hay): sin esa clave, el
volcado de la base no es legible. Dos excepciones, ambas sin secretos ni
payloads:

- Los **ajustes de sync** (endpoint, health check URL, reintentos) viven en
  **SharedPreferences privadas** de la app, no en Room.
- El `inputData` del worker de sync (una copia no-secretos de esa config:
  endpoint, health check, reintentos, método, timeout, tipo de sender) se
  guarda en la **base privada de WorkManager**, **sin SQLCipher** — no
  contiene el secreto HMAC ni el payload del ride.

| Qué | Qué es | Dónde vive |
|---|---|---|
| **Tracks GPS** | Puntos de ubicación del ride (tabla `track_points`) | Room cifrada (SQLCipher) |
| **Descriptores** | Distancia, moving time, velocidades, desnivel, calorías con banda e intervalo (tabla `rides`) | Room cifrada (SQLCipher) |
| **Snapshot de perfil por ride** | Los 4 campos del perfil usados en el cálculo de ese ride (`profile_*` en `rides`) | Room cifrada (SQLCipher) |
| **Perfil corporal** | Peso, altura, edad, sexo (tabla `user_profile`, fila única) | Room cifrada (SQLCipher) — **no** en DataStore ni SharedPreferences |
| **Garaje y desgaste** | Bicis, componentes, eventos de servicio y `bike_id` de cada ride (tablas `bikes`, `components`, `component_events`) | Room cifrada (SQLCipher) |
| **Outbox de sync** | Payloads pendientes de envío (tabla `outbox`); solo existen mientras están pendientes | Room cifrada (SQLCipher) |
| **Ajustes de sync** | Endpoint, health check URL, reintentos (no-secretos) | SharedPreferences privadas de la app |
| **Secreto HMAC** | El secreto con el que se firman los envíos | **Android Keystore**, sellado con AES-GCM; nunca en SharedPreferences en claro ni en logs |

---

## Qué NO se recopila

- **Sin cuentas.** No hay registro, login ni identificador de usuario.
- **Sin analytics.** No hay SDKs de medición de uso.
- **Sin crash reporting con PII.** No hay Firebase Crashlytics ni
  equivalentes; los crashes no se reportan a ningún servidor.
- **Sin publicidad.** No hay anuncios ni perfiles publicitarios.
- **Sin backend propio de BuzzBike.** No hay servidores centrales de
  BuzzBike a los que enviar nada.
- **Sin identificadores de dispositivo** en el payload de sync.
- **Sin telemetría de uso** de ningún tipo.

---

## Cuándo sale algo del dispositivo

Nada sale del dispositivo salvo que **vos** lo actives o lo pidas. Hay
exactamente tres formas:

### (a) Sync opcional a tu propio Hermes/NAS (opt-in)

- **Opt-in, apagado por defecto.** Solo funciona si configurás el endpoint en
  **Ajustes → Sync** y pegás un secreto HMAC generado en tu NAS (32+ bytes).
- El secreto vive **sellado en Android Keystore**; nunca viaja por la red
  (solo se usa para calcular firmas HMAC-SHA256 en los headers).
- **Qué se envía:** métricas agregadas (distancia, moving time, velocidades,
  desnivel, calorías con su banda y modelo) + **waypoints decimados** (máx. 1
  punto cada 10 s, tope de 2000, primer y último siempre conservados).
- **Qué NO se envía:** el track GPS crudo de alta frecuencia, sensores
  crudos, identificadores del dispositivo, el perfil corporal ni el garaje.
- El endpoint debe ser **HTTPS**; se rechaza `http://` en claro.
- **Desactivarlo:** botón **«Desconectar»** en Ajustes → Sync. Cancela el
  trabajo pendiente y **borra los envíos pendientes de la outbox, el secreto
  y la configuración de endpoint/health check** (SharedPreferences). Los
  rides locales no se borran.
- El receptor (tu NAS/Hermes) es tuyo: su retención la definís vos, fuera
  del alcance de esta app. Detalle completo del contrato:
  [`docs/sync-contract.md`](docs/sync-contract.md) y ADR-003.

### (b) Tiles del mapa (al abrir la pestaña MAPA)

- **Sin conexión validada no se instancia el mapa**: aparece un aviso de
  offline con botón `REINTENTAR`. La pestaña MAPA no genera tráfico por sí
  sola.
- **Con conexión validada, al abrir MAPA se piden solo tiles del área
  visible** a **OpenFreeMap** (vector tiles, sin API key; estilo Liberty vía
  MapLibre).
- **Las coordenadas del track NO se envían a nadie.** El track se dibuja en
  local (GeoJSON en memoria); solo se piden tiles del área visible que ya
  estás mirando.
- La atribución (`© OpenStreetMap contributors`, `© OpenMapTiles`,
  `© OpenFreeMap`) es visible en la app por licencia (ODbL).

### (c) Exportación GPX por SAF

- **EXPORTAR GPX** en el detalle del ride escribe un archivo **GPX 1.1 +
  `TrackPointExtension`** a la carpeta que **vos elijas** vía Storage Access
  Framework (`content://`). No se usa permiso de almacenamiento amplio.
- A partir de ahí el archivo es tuyo: BuzzBike no vuelve a leerlo ni a
  enviarlo.

---

## Permisos

| Permiso | Para qué | Notas |
|---|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Trackeo GPS del ride | Se pide en runtime desde la pantalla de ride; hay acción **DAR PERMISO** y **ABRIR AJUSTES** si está bloqueado |
| `INTERNET` | Sync opcional y tiles del mapa | Sin uso si no activás esas funciones |
| `ACCESS_NETWORK_STATE` | Verificar conectividad antes del sync diferido (WorkManager) | No envía nada |
| `POST_NOTIFICATIONS` | Notificación del foreground service de trackeo y alertas de desgaste | Runtime en Android 13+ |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | Foreground service de ubicación (Android 14+ exige type explícito) | — |
| `ACCESS_WIFI_STATE` | **No lo declara BuzzBike**: lo aporta el merge del AAR MapLibre (`android-sdk-opengl`), que lo necesita para detectar tiles disponibles por Wi-Fi | No se usa para nada más; si se reemplaza MapLibre, revisar el merged manifest |
| `WAKE_LOCK` | **No lo declara BuzzBike**: lo aporta el merge del AAR de WorkManager, para mantener el CPU despierto durante el envío diferido del sync | No se usa para nada más; solo lo consume el worker de sync |
| `RECEIVE_BOOT_COMPLETED` | **No lo declara BuzzBike**: lo aporta el merge del AAR de WorkManager, para reprogramar el trabajo encolado tras un reinicio del teléfono | No se usa para nada más; solo lo consume WorkManager |

**No** hay permisos de ubicación en background (`ACCESS_BACKGROUND_LOCATION`)
ni de actividad (`ACTIVITY_RECOGNITION`) en v1. `allowBackup` está en
`false` y `fullBackupContent` en `false`: Android no copia la base a la nube
de Google.

---

## Borrado

- **Borrar la app borra todo.** Los datos viven solo en el almacenamiento
  privado de la app (Room cifrada + SharedPreferences + la base privada de
  WorkManager). No hay copia en ningún servidor de BuzzBike que haya que
  pedir.
- **Borrar el perfil corporal no altera los rides históricos.** Cada ride
  guarda un snapshot de los 4 campos con los que se calculó; borrar o editar
  el perfil solo degrada los cálculos **futuros** a la banda sin perfil
  (±30–40%). Ver ADR-007.
- **Borrar una bici** borra sus componentes y eventos por cascade; los rides
  **conservan** su `bike_id` como referencia histórica suave (el detalle
  muestra «Bici eliminada»). Una bici borrada no acumula desgaste.
- **Desconectar el sync** borra el secreto HMAC, la outbox pendiente **y la
  configuración de endpoint/health check**; los rides locales no se tocan.
- Si exportaste un GPX o enviaste un ride a tu NAS, esa copia es tuya:
  borrala desde donde la guardaste.

---

## Base legal / GDPR (en una línea)

Los tracks GPS son **datos de ubicación sensibles** bajo GDPR y legislaciones
equivalentes; en BuzzBike se tratan **localmente** y **el usuario controla
toda salida** del dispositivo (sync opt-in, tiles del área visible, export
GPX a destino propio).

---

## Contacto

Dudas sobre privacidad: abrí un issue en el repo. Sin cuentas, sin
analytics: no tenemos forma de saber quién sos más allá de lo que vos decís
en el issue.
