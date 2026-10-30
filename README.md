# BuzzBike

<div align="center">

**Computadora de bicicleta que no miente con las calorías.**

Android · Kotlin · Compose · local-first · sin cuentas · MIT

`Kotlin 2.4` · `Jetpack Compose` · `MapLibre` · `minSdk 26` · `MIT` · `sin telemetría`

</div>

---

## Por qué existe esto

Mi Xiaomi me aburrió. Registraba el ride, sí, pero me daba un número de calorías con cara de certeza absoluta y después me pedía una cuenta para ver mis propios datos. Ni idea de qué fórmula usaba, ni cuánto se podía equivocar, ni qué hacía con el track.

Y el número estaba mal. Los relojes comerciales tienen **9–43% de error** contra calorimetría directa ([Wallen 2016, PLOS One](https://doi.org/10.1371/journal.pone.0154420)). Y la energía en bici depende de **potencia × tiempo**, no de la distancia: 20 km rápido en plano y 40 km lento pueden gastar lo mismo. Casi ninguna app te dice eso.

BuzzBike hace lo contrario:

- **Motor de cascada** (potenciómetro → banda de frecuencia cardíaca → METs por velocidad) y te dice **qué modelo aplicó**.
- Muestra siempre el **rango de incertidumbre** (±25–35% sin sensores externos). Nunca un número "exacto".
- **Cero datos personales.** Sin cuenta, sin analytics, sin backend propio. Los tracks GPS no salen del teléfono salvo que vos lo pidas.
- Trackea el **desgaste real de tu bici** (cadena, frenos, neumáticos, suspensión) por kilómetros/horas/días, con alertas al 80% y 100% de la vida útil.

Hecho para la comunidad, en español, y quiero que se use: si le falta algo o una función está mal pensada, abrí un issue y lo discutimos.

---

## Estado actual (honesto)

Verificado contra el código en `main`:

- ✅ **Dominio de sensores y tracking:** `TrackingManager` con puerto `TrackPointWriter`, validación de coordenadas, permiso de ubicación en runtime y persistencia vía `RideRepository`.
- ✅ **Captura GPS:** `LocationSensor` sobre Play Services Location (`FusedLocationProviderClient`), mapeado a un `LocationReading` puro (testeable en JVM sin Android).
- ✅ **Segmentación de traza:** corte por pausas, moving time, distancia, velocidad media/máxima (ADR-005).
- ✅ **Elevación:** fusión de barómetro + altitud GPS con suavizado e histéresis para desnivel positivo/negativo (ADR-006).
- ✅ **Calculadora de calorías** con la cascada MET/ACSM/Keytel y banda de incertidumbre (ADR-001).
- ✅ **Arquitectura:** `ui → domain ← data`, puertos explícitos, Hilt, Room.
- ✅ **Gate verde:** `compileDebugKotlin`, `test` (**58 tests, 0 fallos**), `ktlintCheck`, `detekt` y `assembleDebug` (APK 64 MB) pasan.

**Todavía no:**

- 🚧 **UI de producto:** hay scaffold Compose; las pantallas de ride, mapa y desgaste están en desarrollo.
- ❌ **Sin releases ni APK público.** La primera release firmada sale cuando el flujo end-to-end esté cerrado (GitHub Releases, no Play Store).
- ❌ Sin mapas en producción aún (MapLibre + OpenFreeMap cableado, se activa en el slice de mapas).
- ❌ Sin BLE (frecuencia cardíaca/potencia) ni exportación GPX.

O sea: **el motor está listo y probado, la app todavía no es usable de punta a punta.** Prefiero decirlo antes de que alguien clone y se decepcione. El roadmap con fases y criterios: [`docs/roadmap.md`](docs/roadmap.md).

---

## Lo que la hace distinta: el algoritmo

### Cascada de calorías (ADR-001)

| Nivel | Modelo | Se activa cuando | Error esperado |
|---|---|---|---|
| 1 | ACSM `VO2 = 1.8 × (W × 6.12 / masaKg) + 7` | hay potenciómetro BLE | ±5–10% |
| 2 | Keytel 2005 (con/sin VO2max) | hay banda de frecuencia cardíaca BLE | r = 0.857 |
| 3 | Compendium 2024, METs por bandas de velocidad | solo GPS (caso v1) | ±25–35% |

BMR de reposo con Mifflin-St Jeor. Regla dura: la UI **debe** mostrar el intervalo y el modelo aplicado. "≈250 kcal (±30%, modelo Compendium)" es correcto; "250 kcal" a secas no.

### Elevación real (ADR-006)

El GPS miente en desnivel (en cerros con árboles, bastante). Se fusiona barómetro con altitud GPS, con suavizado e histéresis para no acumular metros fantasma en cada ondulación del terreno.

### Desgaste de componentes (ADR-004)

Cada pieza tiene su ciclo de vida normalizado en tres ejes — kilómetros, horas y días — y alerta al 80% y al 100%. Sin eso, la cadena se cambia cuando ya está destruyendo los piñones.

---

## Privacidad: no trackeamos datos personales

- **Nada sale del dispositivo sin una acción explícita tuya.** No hay backend de BuzzBike. No existe.
- **Sin cuentas, sin analytics, sin crash reporting con datos personales.**
- El sync opcional a un agente local (Hermes Agent, ver abajo) está **apagado por defecto** y manda métricas agregadas + waypoints decimados, no el track crudo de alta frecuencia.
- Los datos viven en Room: borrás la app y se borra todo. Exportación GPX a tu propio storage.

---

## Compatibilidad por API

BuzzBike no te encierra. Dos puertas, ambas abiertas y documentadas:

### Webhooks genéricos firmados (ADR-003)

Cada ride se puede publicar como un `POST` JSON a **la URL que vos quieras** — tu NAS, Home Assistant, n8n, un VPS, un agente de IA, lo que sea.

- Firma `X-Webhook-Signature-V2: HMAC_SHA256(secret, "<timestamp>.<body>")` en hex, con el secret en **Android Keystore** (hardware-backed cuando hay).
- Cola **outbox** en Room + WorkManager con backoff exponencial: la entrega sobrevive a que te quedes sin señal en el cerro.
- **Idempotencia por `ride.id`**: reintentar nunca duplica un ride.
- **Data minimization:** métricas agregadas (distancia, moving time, velocidades, desnivel, calorías con modelo e intervalo) + waypoints decimados. **Nunca** el track crudo ni identificadores del dispositivo, salvo que lo configures explícitamente.

### Conectable a cualquier LLM compatible con la API de OpenAI

El payload es JSON plano y documentado, así que se lo podés pasar a **cualquier endpoint compatible con la API de OpenAI** (chat completions): un agente local, Ollama o vLLM expuesto con el shim de OpenAI, OpenRouter, o un proveedor comercial si te da igual pagarlo. Convertir el payload en un prompt y pedirle la crónica del ride es literalmente un `POST`.

Verificado hoy: funciona contra **Hermes Agent** (agente local self-hosted, que no es el modelo Hermes de Nous Research) corriendo en un NAS por Tailscale.

---

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.4.21 |
| Build | AGP 9.4.1 · Gradle 9.8.2 |
| UI | Jetpack Compose + Material 3 (BOM 2026.09.00) |
| DI | Hilt 2.60.1 (KSP) |
| Persistencia | Room 2.8.5 |
| Background | WorkManager |
| Ubicación | Play Services Location 21.3.0 |
| Mapas | MapLibre Native 13.7.1 + OpenFreeMap (sin API key) |
| Calidad | ktlint 14.2.0 · detekt 1.23.8 · JUnit4 + Turbine |
| minSdk / target | 26 / 36 (Android 15–16) |

---

## Empezar

Prerrequisitos: **JDK 17** y **Android SDK 36** (compileSdk 37 por el AAR metadata de las AndroidX resueltas).

```bash
git clone https://github.com/CrisAlva1414/BuzzBike.git
cd BuzzBike

./gradlew assembleDebug              # APK de debug
./gradlew detekt ktlintCheck test    # gate antes de cualquier PR
```

Guía paso a paso desde una máquina vacía (Linux, sin sudo), con los pitfalls del toolchain: [`docs/setup-entorno.md`](docs/setup-entorno.md).

---

## Ideas y feedback: lo que necesito de vos

Esto se construye en público y le faltan ojos. Lo que más me sirve:

- **Riders:** ¿qué métrica te falta? ¿qué te molesta de tu app actual? ¿qué te haría cambiar de la Garmin o el Wahoo?
- **Devs Android:** revisión del dominio (calorías, elevación, segmentación) — son clases puras y testeables a propósito.
- **Gente de datos y fitness:** ¿el orden de la cascada te parece correcto? ¿Falta Polar o Firstbeat?
- **Self-hosters:** qué formato de payload te gustaría consumir en Home Assistant, Grafana o n8n.

Abrí un [issue](https://github.com/CrisAlva1414/BuzzBike/issues) o un PR. Convenciones en [`AGENTS.md`](AGENTS.md): `./gradlew detekt ktlintCheck test` en verde y Conventional Commits.

---

## Roadmap

- [x] Fundación: ADR-001..006, docs, toolchain
- [x] Scaffold de build + dominio (tracking, descriptores, elevación, calorías) con gate verde
- [ ] Schema Room completo + migraciones
- [ ] UI de captura en vivo (mapa + métricas)
- [ ] Desgaste de componentes con alertas
- [ ] Exportación GPX 1.1
- [ ] Sync a Hermes Agent + webhooks genéricos
- [ ] Primera release firmada (GitHub Releases)
- [ ] Post-v1: BLE frecuencia cardíaca/potencia, PMTiles offline, F-Droid

---

## Documentación

| Qué | Dónde |
|---|---|
| Decisiones de arquitectura | [`docs/adr/`](docs/adr/) — ADR-001 a ADR-006 |
| Roadmap y estado | [`docs/roadmap.md`](docs/roadmap.md) |
| Convenciones de código | [`docs/format/`](docs/format/) |
| Guía de entorno | [`docs/setup-entorno.md`](docs/setup-entorno.md) |
| Bitácora de sesiones | [`docs/sessions/`](docs/sessions/) |

---

## Licencia

MIT — ver [`LICENSE`](LICENSE). Copyright (c) 2026 Cristian Alvarado.

Código abierto, sin nube obligatoria, sin pedirte una cuenta para ver tus propios datos. Si te sirve, una estrella ayuda a que otros ciclistas lo encuentren.
