# Seguridad — BuzzBike

**Última actualización: 2026-11-01.**

BuzzBike no tiene backend propio ni cuentas: la superficie de ataque es la
**app Android** y lo que **vos** configures en tu propio NAS. Este documento
cubre cómo reportar vulnerabilidades, qué versiones soportamos y qué
garantías (y no-garantías) ofrece el sistema.

---

## Cómo reportar una vulnerabilidad

- **Abrí un issue privado en el repo** o **contactá al mantenedor del
  repo** por el canal que uses con él. No hay email de seguridad dedicado:
  preferimos no inventar una dirección que nadie lee.
- Si el hallazgo es sensible (acceso a tracks GPS, fuga del secreto HMAC,
  firma falsificable), decilo en el título del issue o pedí que se maneje
  como privado desde el primer mensaje.
- Contestamos en la medida de lo posible; este es un proyecto con una
  persona y un pipeline agéntico, no un programa de bug bounty.

---

## Versiones soportadas

| Versión | Soporte |
|---|---|
| **1.x** | ✅ Soportada (correcciones de seguridad en 1.x) |
| **0.1.0** (APK de debug, 2026-10-30) | ❌ Histórica, sin soporte — era APK de debug para probar, no para distribuir |
| < 0.1.0 | ❌ Sin soporte |

Los APKs de **debug** nunca se consideran release soportada: solo sirven
para probar. La vía de instalación soportada es la **release firmada** del
job de CI en tags `v*` (ver `README.md` §Descargar).

---

## Alcance

**Dentro del alcance:**

- La app Android (`com.buzzbike.app`): UI, dominio, persistencia local
  (Room cifrado), sync, export GPX, permisos, foreground service.
- El **contrato de sync** (HMAC V2, payload, health check, ventana
  anti-replay): la implementación del lado app de
  [`docs/sync-contract.md`](docs/sync-contract.md).

**Fuera del alcance:**

- **El NAS / Hermes Agent es del usuario.** Su configuración, retención,
  exposición de puertos y autenticación no son responsabilidad de BuzzBike:
  vos lo desplegás y vos lo asegurás (Tailscale, HTTPS, secreto compartido).
- El receptor del webhook (cualquier endpoint que configures: Hermes,
  Home Assistant, n8n, un VPS…).
- El APK de **debug** firmado con la clave de debug de desarrollo.
- La **firma de release**: el keystore y los secrets de CI son
  responsabilidad del mantenedor; el job de CI falla sin ellos y no
  publica nada (fail-closed).

---

## Garantías de seguridad

| Garantía | Cómo se implementa | Referencia |
|---|---|---|
| **Cifrado en reposo** | Room + **SQLCipher** sobre toda la base (`rides`, `track_points`, `user_profile`, `bikes`, `components`, `component_events`, `outbox`). Passphrase sellada en **Android Keystore** (AES-GCM, hardware-backed cuando hay). | ADR-003, ADR-007; test instrumentado `EncryptedDatabaseInstrumentedTest` |
| **HTTPS-only** | `network_security_config.xml`: `cleartextTrafficPermitted="false"` en todo, incluidos dominios locales. El sync rechaza endpoints `http://`. | `app/src/main/res/xml/network_security_config.xml`; validación en `HermesConfig` / `HttpConnectivityChecker` |
| **HMAC en cada envío** | Firma `X-Webhook-Signature-V2: HMAC_SHA256(secret, "<timestamp>.<body>")` en hex, con ventana anti-replay ±300 s del lado del receptor. Sin secreto provisionado **no hay sync** (falla de configuración, no reintento). | ADR-003, `docs/sync-contract.md` §3 |
| **Secreto en Keystore** | El secreto HMAC se guarda **sellado con AES-GCM usando una clave de Android Keystore**, nunca en SharedPreferences plaintext ni en logs. La UI no lo vuelve a mostrar (write-only). | `AndroidKeystoreSecretSealer`, `HmacSecretManager`; test `KeystoreHmacInstrumentedTest` |
| **Release firmada y verificable** | El job `release` (tags `v*`) construye `assembleRelease` y publica `app-release.apk` + **`app-release.apk.sha256`**. Verificar con `sha256sum -c app-release.apk.sha256`. Exige los **5 secrets** de firma (4 de keystore + `RELEASE_CERT_SHA256`); sin ellos **falla sin publicar nada**. Además **rechaza** APKs con `android:debuggable` y certificados `CN=Android Debug`. | `.github/workflows/android.yml`, job `release` |
| **Sin copia de seguridad en la nube** | `android:allowBackup="false"` y `fullBackupContent="false"`: Android no sube la base a Google Drive. | `AndroidManifest.xml` |
| **Data minimization en el sync** | Solo métricas agregadas + waypoints decimados (máx. 1 punto/10 s, tope 2000). Nunca track crudo, PII ni identificadores de dispositivo. | ADR-003, `docs/sync-contract.md` §8 |
| **Idempotencia** | Dedupe por `rideId` en la outbox: reintentar nunca duplica un ride en el receptor. | ADR-003, `docs/sync-contract.md` §6 |
| **Sin telemetría** | Cero SDKs de analytics/crash-reporting. Nada sale del dispositivo sin acción explícita. | [`PRIVACY.md`](PRIVACY.md) |

### Garantías que NO damos (honestidad)

- **No hay certificate pinning** en v1: se usa el trust store del sistema.
  Un NAS con CA self-signed requiere instalar esa CA en el dispositivo.
  (ADR-003, actualización 2026-10-28.)
- **No hay gate biométrico** para leer la base: quien tenga el dispositivo
  desbloqueado con la app instalada puede usarla. El cifrado protege contra
  extracción del archivo, no contra uso con el teléfono desbloqueado.
- **El E2E del sync contra un NAS con Hermes real no está validado**; la
  spec está testeada contra mock. Eso es deuda de release, no una garantía.
- **`ACCESS_WIFI_STATE`** entra por el AAR de MapLibre: es un permiso de la
  dependencia, no una decisión de BuzzBike para leer tu Wi-Fi.

---

## Estado de la revisión de seguridad

Cada slice que toca **secretos o red** cierra con `security-reviewer: PASS`
(explicitado en `state/` y en el roadmap §4). U1.1, U2, U3, U4, U5 y U6
cerraron con **0 hallazgos abiertos**; el slice de sync cerró con FAIL →
remediación → PASS. El **gate final de release** vuelve a correr
`security-reviewer` sobre el diff acumulado antes del tag.
