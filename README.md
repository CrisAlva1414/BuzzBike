# BuzzBike

> App Android open source de trackeo de rutas de bicicleta.
> **Proyecto en desarrollo temprano (WIP).** No hay releases todavía. Este README describe el estado actual y lo planeado.

BuzzBike registra tus rides con el GPS y los sensores del teléfono, calcula descriptores de la ruta (distancia, velocidad, desnivel, moving time) y estima el gasto calórico **mostrando siempre la incertidumbre**, nunca un número presentado como exacto. Además trackea el desgaste de los componentes de tu bici (cadena, suspensión, neumáticos, frenos) con alertas cuando se acerca el momento del cambio.

**Filosofía:** local-first, auditable, sin cuenta ni nube obligatoria. Tus datos viven en el dispositivo. El sync a un agente de IA local es opcional y solo sale de la app si vos lo activás.

---

## Features planeadas (v1)

- **Trackeo de rides** con GPS + sensores inerciales del teléfono (acelerómetro, giroscopio, barómetro si existe).
- **Descriptores de ride:** distancia, velocidad media/máxima, desnivel positivo/negativo, moving time.
- **Gasto calórico honesto:** motor de cascada (ACSM si hay potencia → Keytel si hay HR → Compendium por bandas de velocidad si no hay nada más). La UI muestra el intervalo de incertidumbre y qué modelo se usó. Detalle en [ADR-001](docs/adr/ADR-001-modelo-calculo-calorico.md).
- **Desgaste de componentes:** ratios por kilómetros/horas/días con alertas al 80% y 100% del ciclo de vida, e historial de services. Detalle en [ADR-004](docs/adr/ADR-004-wear-tracking-maximos-normalizados.md).
- **Mapas online** con MapLibre Native + OpenFreeMap (sin API key). Offline PMTiles queda para una versión futura. Detalle en [ADR-002](docs/adr/ADR-002-mapas-maplibre-openfreemap.md).
- **Sync opcional** a Hermes Agent (framework de Nous Research) corriendo en tu NAS, vía webhook firmado sobre Tailscale. Detalle en [ADR-003](docs/adr/ADR-003-hermes-agent-webhook-hmac.md).
- **Exportación GPX 1.1** con TrackPointExtension.

## Qué NO es BuzzBike

- No es una red social (sin feed, follows ni ranking).
- No es un reloj deportivo de precisión de sensores.
- No calcula calorías "exactas" — sin perfil la banda es ±30–40%, con perfil ±20–30% (ADR-001). Lo dice la app, no lo escondemos.
- No usa Play Store. La distribución es vía GitHub Releases (APK).
- No tiene backend propio ni cuentas de usuario.

---

## Estado actual

| Área | Estado |
|---|---|
| Documentación base | ✅ Creada (AGENTS.md, ADRs, formato, research) |
| Código de producción | ❌ Aún no empezado |
| Build de Android | ❌ Bloqueado: falta JDK 17+ y Android SDK en la máquina de desarrollo |
| Releases | ❌ Ninguna todavía |

El plan de implementación está dividido en 8 slices; el detalle está en [`docs/sessions/2026-10-02-sprint-00-discovery.md`](docs/sessions/2026-10-02-sprint-00-discovery.md).

---

## Cómo compilar

> **Prerrequisito (pendiente):** JDK 17+ y Android SDK. Sin esto, Gradle no corre.

Cuando el toolchain esté instalado:

```bash
git clone <URL_DEL_REPO>
cd BuzzBike

# Build de debug
./gradlew assembleDebug

# Unit tests + linters (correr antes de cada PR)
./gradlew detekt ktlintCheck test

# APK de release (para GitHub Releases)
./gradlew assembleRelease
```

La distribución es un APK desde GitHub Releases — no hay build de Play Store ni AAB de producción.

---

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Persistencia | Room |
| Background | WorkManager |
| Mapas | MapLibre Native Android + OpenFreeMap |
| BLE (futuro HR/potencia) | Nordic Android BLE Library 2.11.x |
| minSdk / Target | 26 / Android 15–16 (API 35/36) |

---

## Privacidad

BuzzBike es **local-first**:

- Los tracks GPS son datos de ubicación sensibles. **Nada sale del dispositivo sin acción explícita tuya.**
- El sync a Hermes Agent es opcional, off por defecto, y por defecto envía solo métricas agregadas + waypoints decimados — no el track crudo de alta frecuencia.
- Sin analytics de terceros, sin cuentas, sin crash reporting que envíe PII.
- Los datos viven en Room. Borrar la app borra todo. La exportación es GPX a tu storage.

---

## Documentación

| Qué | Dónde |
|---|---|
| Guía para agentes y colaboradores | [`AGENTS.md`](AGENTS.md) |
| Decisiones de arquitectura (ADRs) | [`docs/adr/`](docs/adr/) |
| Convenciones de código | [`docs/format/`](docs/format/) |
| Bitácora de sesiones | [`docs/sessions/`](docs/sessions/) |
| Índice de investigación | [`research/`](research/) |

---

## Licencia

MIT — ver [`LICENSE`](LICENSE).

> **TODO:** el copyright holder del LICENSE está como placeholder `[TU NOMBRE]` hasta que se confirme el titular.
