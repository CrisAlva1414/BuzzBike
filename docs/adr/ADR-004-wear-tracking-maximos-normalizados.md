# ADR-004: Wear tracking por máximos normalizados

- **Estado:** Aceptado
- **Fecha:** 2026-10-02
- **Decisor:** Usuario

---

## Context

BuzzBike trackea el desgaste de componentes de la bici (cadena, suspensión, neumáticos, frenos) y alerta cuando un componente se acerca o alcanza el final de su ciclo de vida. Restricciones y hallazgos:

- **No existe un estándar de intercambio de datos de wear entre apps de ciclismo.** No hay schema compartido ni API pública para esto.
- **Prior art conocido (verificar antes de reinventar):**
  - `liskin/strava-gear` — tracking de gear en el ecosistema Strava
  - `bikenace-app`
  - `higorslva/biketrackd` — Android Kotlin + Compose + MapLibre + OpenFreeMap + wear % + alerts. Casi idéntico al alcance de BuzzBike. **Revisar su implementación antes de codear este módulo.**
- Cada componente se desgasta por **múltiples ejes** con distinto ciclo de vida: la cadena por kilómetros, la suspensión por horas de servicio, los neumáticos por kilómetros, los frenos por kilómetros + frenado.
- El usuario quiere alertas proactivas ("te va a tocar cambiar X"), no solo un número en una pantalla.

---

## Decision

### Fórmula: ratio de desgaste por máximos normalizados

Para cada componente, el desgaste se expresa como un ratio normalizado entre 0 y >1:

```
wearRatio = max(
    km        / lifecycleKm,
    hours     / lifecycleHours,
    days      / lifecycleDays
)
```

- Se toma el **máximo** de los ratios individuales: el componente se considera gastado en el primer eje que se agota.
- `wearRatio > 1` = ciclo de vida excedido.

### Umbrales de alerta

| Evento | Condición | Semántica |
|---|---|---|
| `approaching` | `wearRatio >= 0.80` (80%) | "Se te acerca el cambio" |
| `due` | `wearRatio >= 1.00` (100%) | "Toca cambiar / ya vencido" |

### Evento `service` y snapshot

- Cuando el usuario registra un **service** (cambio de componente, ajuste, servicio), el sistema:
  1. Resetea el acumulado del componente.
  2. Guarda un **snapshot previo** en la tabla `component_events` (qué se había consumido, cuándo, wearRatio al momento del service, notas opcionales).
- El histórico queda consultable: "la última vez que cambié la cadena eran 2.100 km".

### Defaults de ciclo de vida (v1)

| Componente | Default | Ejes |
|---|---|---|
| Cadena | 2000 km | km |
| Suspensión | 100 h (o 50 h para service parcial) | hours |
| Neumáticos | 3000–5000 km (rango configurable por el usuario) | km |
| Frenos | Correlaciona con **km + desnivel negativo** (proxy de frenado) | km + descent |

**Nota sobre frenos:** no hay un estándar de "ciclo de vida de frenos" tan claro como el de la cadena. Se usa como proxy la combinación de distancia acumulada y desnivel negativo (más frenado = más desgaste de pastillas/botas). El factor de ponderación entre km y desnivel negativo queda como **TODO configurable**, no como constante inventada.

Los defaults son **defaults, no verdades de fábrica.** El usuario puede (y debe poder) ajustarlos por componente y por bike. La UI debe mostrar claramente el default usado y permitir editarlo.

### Persistencia

- Tablas Room: `components` (definición, bike_id, lifecycle thresholds, tipo), `component_events` (snapshots de service), y acumulados calculados a partir de rides + eventos.
- El cálculo de `wearRatio` vive en `domain/` (JVM puro, testeable).

---

## Consecuencias

- **Un solo modelo para todos los componentes.** Agregar un componente nuevo (p. ej. transmisión, suspensión trasera) es una fila de config + sus ejes, no código nuevo de dominio.
- **El máximo normalizado es simple y auditable.** Cualquier persona puede reproducir el cálculo a mano con los datos del ride. No hay caja negra.
- **Las alertas son accionables.** 80% y 100% son los dos momentos en que un ciclista decide: planificar el cambio o hacerlo ya.
- **El snapshot en `component_events` da historial de mantenimiento.** Esto es valor real a largo plazo: la app se vuelve el log de service de la bici.
- **El proxy de frenos es una aproximación honesta.** Se documenta como proxy; no se presenta como medición directa de desgaste de pastillas. Si en el futuro hay sensor de desgaste de frenos, se enchufa como eje adicional.
- **Sin estándar de intercambio = sin integración automática con otras apps en v1.** El prior art se revisa para no reinventar, pero no se promete sync de gear con Strava/etc.
- **Los defaults pueden estar mal para una bici concreta.** La UI debe empujar al usuario a revisarlos, no esconderlos.

---

## Alternativas descartadas

### Modelo por componentes separados sin fórmula común
**Descartado.** Cada componente con su lógica ad-hoc duplicaría código, haría imposible el testing uniforme y dificultaría añadir componentes nuevos. El ratio normalizado cubre todos los casos con una fórmula.

### Estimar wear solo por tiempo (días desde el último service)
**Descartado.** Un ciclista que hace 200 km/semana no se desgasta igual que uno que hace 20. El tiempo sin kilómetros no desgasta la cadena. Los ejes km/horas/days existen justamente para capturar el uso real.

### Integración nativa con Strava Gear API
**Descartado.** Requiere cuenta de Strava, OAuth, TOS de terceros, y contradice el local-first de BuzzBike. Además no hay API pública de gear que cubra el caso completo. Se puede considerar como sync opcional futuro, nunca como fuente primaria.

### Usar datos de proveedores de componentes (Shimano/SRAM calculadoras oficiales)
**Descartado.** Las calculadoras oficiales son black boxes web, sin API estable, y sus números son de marketing/mantenimiento, no un estándar de medición. BuzzBike necesita un modelo auditable propio.

### ML para predecir desgaste restante
**Descartado.** Sin dataset etiquetado de "este componente duró X km y falló", no hay forma de entrenar ni validar. El ratio normalizado es suficiente para v1 y es explicable al usuario.

### Mostrar solo "faltan 400 km" sin porcentaje ni umbral
**Descartado.** Un número absoluto sin contexto no genera acción. El ratio + los umbrales 80%/100% son lo que convierte el dato en una decisión.

---

## Fuentes / prior art

| Fuente | Referencia | Nota |
|---|---|---|
| liskin/strava-gear | https://github.com/liskin/strava-gear | Prior art de gear tracking en ecosistema Strava |
| bikenance-app | TODO: verificar URL exacta del repo | Prior art mencionado en investigación |
| higorslva/biketrackd | https://github.com/higorslva/biketrackd | Prior art Android casi idéntico — **revisar antes de codear** |
| Compendium / estándares de vida útil | — | No hay estándar público único; los defaults de cadena/suspensión/neumáticos son consenso de la industria, no un paper. Documentar como defaults configurables. |

---

## Actualización 2026-11-01 — implementación en U4

> **Alcance.** Registra las decisiones tomadas al **implementar** este ADR en el
> slice U4 (Garaje y desgaste): `domain/wear/` (`WearEngine`,
> `ComponentDefaults`, repositorios), las tablas de la DB **v6** y la UI de
> garaje. No reescribe las secciones anteriores; donde haya conflicto, prevalece
> este texto por ser el más reciente.

### 1. `lifecycleDays` es opcional

- `WearLifecycle.lifecycleDays` quedó como **nullable, sin default**. El ADR no
  fijaba uno y **no se inventó**: un eje `null` simplemente **no se evalúa** en
  el `max()` de la fórmula (junto con `lifecycleKm`/`lifecycleHours` también
  opcionales).
- Un componente queda sin ejes declarados solo si el usuario lo deja así; en
  ese caso no hay ratio que calcular.

### 2. Frenos: sin ciclo de vida por defecto

- **No se implementó default de frenos.** El ADR (§Defaults) define el proxy
  "km + desnivel negativo" pero **no fija factor de ponderación** (era TODO
  configurable), y sin ese factor no hay umbral que inventar.
- En la práctica: los frenos **requieren que el usuario configure el ciclo de
  vida en km** antes de que se evalúen. El **desnivel negativo queda como TODO
  informativo** en el modelo — **no entra en la fórmula** de `wearRatio`.

### 3. Defaults implementados (`ComponentDefaults`)

| Componente | Default implementado | Eje |
|---|---|---|
| Cadena | 2000 km | km |
| Suspensión | 100 h | hours |
| Neumáticos | **3000 km** (mínimo del rango 3000–5000 km, configurable) | km |
| Frenos | **sin default** (ver §2) | — |

Siguen siendo **defaults, no verdades de fábrica**: editables por componente y
por bici, como manda §Defaults.

### 4. Umbrales y estados globales

- `approaching` → `wearRatio >= 0.80`; `due` → `wearRatio >= 1.00`, tal cual §Umbrales.
- Estados derivados en el dominio: **`OK` / `APPROACHING` / `DUE`** (`WearStatus`).
- No hay umbrales por tipo de componente: **uno global**, igual para todos.

### 5. Acumulación de uso

- **Solo los rides asignados a esa bici** (`rides.bike_id`) suman km/horas hacia
  su desgaste. Un ride sin bici no acumula en ningún componente.
- **Base de acumulación:** el último evento `SERVICE` del componente; si nunca
  tuvo service, `installedAtMillis`. El `SERVICE` resetea el acumulado y deja
  snapshot previo en `component_events` (§Evento `service`).
- **El eje días se mide desde esa misma base** con un **reloj inyectable**
  (`WearClock`), testeable en JVM sin dormir.

### 6. Borrado de bici — contrato de "referencia histórica"

- Al borrar una bici, los rides **conservan su `bike_id`** (no se limpia, no se
  borran): es una **referencia histórica suave**. En el detalle del ride, si la
  bici ya no existe, se muestra el label **"Bici eliminada"** en vez de fallar
  la carga.
- Componentes y eventos de esa bici **se borran por CASCADE** (FK Room sobre
  `components`/`component_events`).
- Una bici borrada **no acumula desgaste**: sus componentes ya no existen, y la
  UI de garaje solo evalúa bicis presentes.

### 7. Persistencia — DB v6 (migración 5→6)

| Elemento | Detalle |
|---|---|
| `bikes` | id, name, notes, createdAtMillis, isArchived |
| `components` | ciclo de vida nullable por eje, FK → `bikes` **CASCADE** + índice |
| `component_events` | snapshots de service, FK → `components` **CASCADE** + índice |
| `rides.bike_id` | columna **nullable** + índice `index_rides_bike_id` |

- **Sin FK dura de `rides` → `bikes`:** SQLite **no permite añadir una foreign
  key con `ALTER TABLE`** (habría que reconstruir la tabla), y una FK dura
  además **bloquearía el borrado** de bicis con rides asociados. Por eso la
  relación es **soft reference** (§6) y la consistencia la da la capa de
  dominio/repo, no el schema.
