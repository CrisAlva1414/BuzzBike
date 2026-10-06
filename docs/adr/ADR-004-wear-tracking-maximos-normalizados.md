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
