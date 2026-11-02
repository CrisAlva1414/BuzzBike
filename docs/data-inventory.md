# Inventario de datos — BuzzBike

**Última actualización: 2026-11-01.** Qué datos existen, dónde viven, si
están cifrados, si salen del dispositivo y cuándo se borran. La narrativa de
privacidad está en [`PRIVACY.md`](../PRIVACY.md); este documento es la
tabla operativa.

**Convenciones:**

- **Cifrado en reposo:** todo lo marcado «Room cifrado» es SQLite vía
  **SQLCipher**, con passphrase sellada en **Android Keystore** (AES-GCM,
  hardware-backed cuando hay). La misma frontera de cifrado cubre todas las
  tablas de la `AppDatabase`.
- **«¿Se envía?»** solo puede ser SÍ con acción explícita del usuario
  (sync opt-in). No hay envío automático, ni de fondo, ni de telemetría.

---

## Tabla maestra

| Dato | Qué es | Dónde se guarda | Cifrado | ¿Se envía? | Retención / borrado |
|---|---|---|---|---|---|
| **Tracks GPS** | Puntos crudos del ride: lat, lon, tiempo, altitud, precisión, velocidad, presión (tabla `track_points`) | Room, DB local | SQLCipher (Room cifrada) | **NO** — nunca se envía el track crudo. Solo waypoints decimados en el payload de sync | Vive hasta que borrás la app. Borrado de un ride: borra sus track_points (FK) |
| **Descriptores del ride** | Distancia, moving time, velocidades media/máxima, desnivel +/−, calorías (estimación + banda + modelo), estado activo (tabla `rides`) | Room, DB local | SQLCipher | **SÍ, parcial** en sync opt-in: solo las métricas agregadas y la banda calórica (no el track). Ver `docs/sync-contract.md` §5 | Vive hasta que borrás la app o el ride |
| **Snapshot de perfil por ride** | Los 4 campos del perfil usados en el cálculo de ese ride (`profile_weight_kg`, `profile_height_cm`, `profile_age_years`, `profile_is_male` en `rides`) | Room, DB local (columnas de `rides`) | SQLCipher | **NO** — nunca entra al payload de sync | Se congela al cerrar el ride. Borrar el perfil vivo **no** lo altera (es dato del ride). Vive con el ride |
| **Perfil corporal** | Peso, altura, edad, sexo del usuario (tabla `user_profile`, fila única) | Room, DB local | SQLCipher (misma frontera que los rides; **no** en DataStore ni SharedPreferences) | **NO** — nunca fuera del dispositivo (ADR-007) | Vive hasta que lo borrás desde Ajustes (confirmación) o desinstalás la app |
| **Garaje — bicis** | Nombre, notas, archivado (tabla `bikes`) | Room, DB local | SQLCipher | **NO** en v1 (el estado de wear no viaja en el payload actual) | Borrar bici: borra componentes y eventos por CASCADE; los rides conservan `bike_id` suave. Archivar no borra nada |
| **Garaje — componentes** | Tipo, label, ciclo de vida por eje km/horas/días (tabla `components`) | Room, DB local | SQLCipher | **NO** | Se borran con su bici (CASCADE) o al «RETIRAR» el componente |
| **Garaje — eventos de servicio** | Snapshot previo al service: km/horas consumidos, fecha, ratio, notas (tabla `component_events`) | Room, DB local | SQLCipher | **NO** | Se borran con el componente (CASCADE). Historial de mantenimiento local |
| **Outbox de sync** | Payloads JSON pendientes de entrega (tabla `outbox`): métricas agregadas + waypoints decimados ya serializados | Room, DB local | SQLCipher | Es el **propio envío** (se drena al endpoint configurado). Solo existe mientras está PENDING/FAILED | Se borran las filas entregadas y las fallidas agotadas a los **7 días** (`FAILED_RETENTION_MILLIS`); al **Desconectar** se purga la outbox completa |
| **Secreto HMAC** | Secreto compartido con el receptor (32+ bytes), para firmar `X-Webhook-Signature-V2` | **Android Keystore**, blob sellado con AES-GCM (clave de Keystore) | Sellado en Keystore (no es plaintext en disco) | **NUNCA se transporta por la red** — solo se usa para calcular firmas | Se borra al tocar **Desconectar** en Ajustes o al desinstalar la app (clave de Keystore) |
| **Passphrase de la DB** | Clave que SQLCipher usa para abrir la base | **Android Keystore**, blob sellado con AES-GCM | Sellado en Keystore | **NUNCA** | Vive con la app. Si se pierde la clave de Keystore, la base queda ilegible (documentado como riesgo en ADR-003) |
| **Ajustes de sync** | Endpoint HTTPS, health check URL HTTPS, cantidad de reintentos (campos no-secretos) | SharedPreferences privadas de la app (`MODE_PRIVATE`); una copia sin secretos (endpoint, health check, reintentos, método, timeout, tipo de sender) viaja en el `inputData` del worker, guardado en la **base privada de WorkManager** (sin SQLCipher; sin secreto ni payload) | **Sin cifrar extra** (SharedPreferences del sandbox de la app; no contienen secretos) | Las **URLs** viajan en requests de red cuando hay sync activo (health check y envío) | Se borran al tocar **Desconectar** (toda la config, incluidos endpoint y health check) o al desinstalar la app; se pueden editar/re-guardar desde Ajustes mientras el sync siga configurado |
| **Preferencias de UI** | Estado transitorio de pantallas (selector de bici, etc.) | Memoria / estado de Compose + SharedPreferences privadas si persiste | Sandbox de la app | **NO** | Vive con la app; se borra al desinstalar |

---

## Lo que sale del dispositivo (resumen)

Solo hay **tres** vías de salida, todas bajo control del usuario:

| Vía | Cuándo | Qué sale | Control |
|---|---|---|---|
| **(a) Sync a tu Hermes/NAS** | Opt-in en Ajustes; solo con red y endpoint configurado | Métricas agregadas + waypoints decimados (máx. 1 punto/10 s, tope 2000), firmados con HMAC-SHA256 sobre HTTPS | Toggle: configurar = activar; **Desconectar** = borrar secreto, outbox y ajustes de endpoint |
| **(b) Tiles del mapa** | Solo al abrir la pestaña **MAPA** del detalle | Requests de tiles del **área visible** a OpenFreeMap. **Las coordenadas del track no se envían**: el track se dibuja en local | Pestaña MAPA no genera tráfico; sin conexión validada no se instancia el mapa |
| **(c) Exportación GPX** | Tocar **EXPORTAR GPX** en el detalle | Un archivo GPX 1.1 + TrackPointExtension escrito a la carpeta que el usuario elija (SAF, `content://`) | El usuario elige destino; BuzzBike no relee ni reenvía ese archivo |

---

## Lo que NO existe

- **Sin cuentas / IDs de usuario.** No hay registro ni login.
- **Sin analytics / telemetría.** Cero SDKs de medición de uso.
- **Sin crash reporting con PII.** No hay Crashlytics ni equivalentes.
- **Sin publicidad.** No hay ads ni perfiles publicitarios.
- **Sin identificadores de dispositivo** en el payload de sync.
- **Sin backend propio de BuzzBike.** No hay servidores centrales.
- **Sin backup a la nube:** `allowBackup=false`, `fullBackupContent=false`.

---

## Borrado — resumen por acción

| Acción del usuario | Qué se borra | Qué NO se borra |
|---|---|---|
| **Borrar la app** | Todo: DB cifrada, SharedPreferences, blobs de Keystore locales, outbox, perfil, garaje, tracks | Copias GPX exportadas a storage del usuario; copias en tu NAS si activaste sync |
| **Borrar el perfil** (Ajustes) | Fila `user_profile` | Rides, tracks, snapshots de perfil por ride, garaje |
| **Borrar una bici** (Garaje) | `bikes` + `components` + `component_events` (CASCADE) | `rides` (conservan `bike_id` como referencia histórica suave; el detalle muestra «Bici eliminada») |
| **Retirar un componente** | Esa fila de `components` y sus eventos | La bici y los demás componentes |
| **Desconectar sync** (Ajustes) | Secreto HMAC (Keystore), **ajustes de endpoint** (endpoint, health check, reintentos — SharedPreferences `sync_config_v1`), outbox completa (todas las filas), trabajo encolado | Rides, tracks, perfil, garaje |

---

## Notas de retención

- **No hay retención automática por tiempo para rides, perfil ni garaje:**
  esos datos viven hasta que vos los borrás o desinstalás la app; BuzzBike no
  los expira en background. La única excepción es la **outbox**: sus filas
  entregadas se borran tras el drenaje y las fallidas agotadas a los **7 días**
  (`FAILED_RETENTION_MILLIS` en `OutboxProcessor`).
- **El receptor del sync es tuyo:** la retención de lo que llegue a tu
  NAS/Hermes la definís vos. Recomendación del contrato
  (`docs/sync-contract.md` §8): conservar el ride solo mientras sea útil y
  borrarlo a pedido del usuario. BuzzBike no controla esa copia.
- **Keystore y migración de dispositivo:** al cambiar de teléfono, el
  secreto HMAC y la passphrase de la base **no** se migran (la clave de
  Keystore no se exporta). Se reconfigura el sync pegando el mismo secreto
  de nuevo; la DB cifrada se puede exportar/importar solo si se implementa
  esa feature (hoy no existe; ver ADR-003).
