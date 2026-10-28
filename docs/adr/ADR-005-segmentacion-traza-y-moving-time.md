# ADR-005: Segmentación de traza y tiempo en movimiento

- **Estado:** Propuesto (a aceptar por el usuario)
- **Fecha:** 2026-10-27
- **Decisor:** Usuario (pendiente de aceptación)
- **ADRs relacionados:** ADR-001 (el motor calórico consume `moving` y la velocidad en movimiento), ADR-004 (wear tracking no depende de esta segmentación, pero comparte la regla de citar fuentes)
- **Alcance:** contrato de dominio de `TrackPoint`, limpieza de traza, detección de paradas, cálculo de distancia y agregados de ride. **No** implementa código, **no** define el modelo de elevación/desnivel y **no** define cadencia ni potencia.

---

## Estado de implementación (v1, 2026-10-27)

Implementado en `domain/`:
- `TrackPoint` con campos opcionales `Double?` (sin `NaN` como sentinel).
- Limpieza: orden por tiempo monotónico, dedupe de timestamp, rechazo de lat/lon/accuracy inválidos, teleport de un paso >25 m/s, corte de segmento >60 s, lower bound por gaps/rechazos.
- Velocidad **solo Doppler** (`Location.getSpeed()`); sin fallback por distancia/tiempo. Un fix sin velocidad usable se trata como gap/sin evidencia.
- Máquina moving/stopped con histéresis, vetos de desplazamiento y acelerómetro, override de reanudación, backdating, gaps 5–60 s y reset a STOPPED en gaps >60 s.
- Distancia WGS84 vía puerto `GeodesicDistance` (Haversine para tests JVM).
- Agregados: elapsed/moving/stopped, avgMoving/avg, maxSpeed p95 sobre mediana centrada 5 s.

Diferido (no implementado en v1):
- `TrackPointFlag`/`LOW_QUALITY` como enum (v1 usa `accuracyH <= 15 m` directamente).
- `STATE_UNCERTAIN`/`movingTimeUncertain`.
- Teleport sostenido >60 km/h por >5 s.
- Interpolación de gaps <=5 s.
- Adaptador WGS84 real (`Location.distanceBetween`) y `parametersHash`.

Si el cuerpo de este ADR menciona flags, `NaN` o fallback por desplazamiento, prevalece esta sección para v1.

---

## Contexto

### Sensores disponibles y restricciones

BuzzBike v1 corre en un teléfono Android sin HR strap ni potenciómetro. Las fuentes de datos son:

| Fuente | Disponibilidad | Uso en este ADR |
|---|---|---|
| GPS / FusedLocation | Siempre (con permiso de ubicación) | Posición, tiempo, velocidad Doppler, accuracy horizontal y vertical. |
| Barómetro | Opcional (no todos los teléfonos) | Tendencia de presión/altitud relativa. **No** se usa para decidir moving/stopped. |
| Acelerómetro | Prácticamente siempre | Veto de parada y mantenimiento de estado durante gaps. **No** se usa para cadencia ni potencia. |

No hay sensor de velocidad de rueda, ni IMU calibrada, ni reloj deportivo. Por lo tanto, la segmentación debe ser **explícita, conservadora y auditable**, no una caja negra.

### Por qué separar `elapsed` de `moving`

`elapsed` es el tiempo de reloj entre el primer y el último punto aceptado. `moving` es el tiempo en el que hay evidencia suficiente de desplazamiento. Si se usa `elapsed` para la velocidad promedio, un ride urbano con semáforos largos queda castigado: el ciclista puede haber pedaleado 20 minutos y esperado 10, y la velocidad promedio parece un 33% menor. Eso contamina:

- La lectura de rendimiento del usuario.
- La banda de velocidad que consume el motor calórico de ADR-001 (nivel 3, Compendium).
- La comparación entre rides del mismo recorrido.

Pero `elapsed` sigue siendo necesario: es la base del tiempo total, del componente de reposo del gasto calórico y de la honestidad de la UI. **No se reemplaza uno por otro; se reportan ambos.** Mostrar ambas velocidades y no una sola es una **decisión de producto**, no una consecuencia matemática: comunica que el número depende de una definición y evita vender una precisión que el sensor no tiene.

### Lo que la literatura no resuelve

No hay un estándar único para stop detection en ciclismo. La literatura y las implementaciones usan definiciones heterogéneas:

| Dimensión | Rango reportado | Implicancia |
|---|---|---|
| Umbral de velocidad para considerar parada | 0.3–1.9 m/s | Un mismo ride puede tener 0 o 10 paradas según el umbral. |
| Ventana temporal de confirmación | 15–120 s | Ventanas largas pierden semáforos cortos; ventanas cortas generan falsos positivos por ruido. |
| Definición de "stop" | Punto quieto, región de diámetro máximo, ausencia de movimiento neto | No son equivalentes. |

Fuentes de referencia: Wang et al. 2024 (CycleTrajectory) reporta un criterio de 0.3 m/s durante 20 s; Berjisian & Bigazzi 2022 comparan métodos para distinguir viajes de actividades en datos GPS de caminata y ciclismo; MovingPandas implementa stops por diámetro máximo y duración mínima. Los valores exactos de cada paper deben confirmarse en su sección de métodos antes de fijar un default (ver **Pendientes**).

**Consecuencia directa:** cualquier valor por defecto de este ADR es una **decisión de ingeniería**, no un estándar científico. Se documenta como tal y se versiona.

### Advertencia sobre `Location.getAccuracy()`

`Location.getAccuracy()` de Android devuelve un **radio horizontal al percentil 68**, no al 95%. Es decir: con `accuracyH = 30 m`, aproximadamente el 68% de las posiciones verdaderas caen dentro de ese radio; el 95% es sustancialmente mayor. Por lo tanto:

- `accuracyH` **no** es una cota dura de error.
- Un filtro de 30 m no garantiza que el error máximo sea 30 m.
- No se debe usar `accuracyH` como si fuera un intervalo de confianza del 95% en la UI ni en las métricas.
- La velocidad Doppler es una fuente independiente y, en condiciones normales, más estable que la derivada de posición; aun así tiene su propio error (ver tabla de parámetros).

### Definiciones operativas

| Término | Definición en este ADR |
|---|---|
| **Traza cruda** | Secuencia de fixes tal como llegan del proveedor de ubicación. |
| **Traza limpia** | Secuencia ordenada por tiempo monotónico, sin puntos rechazados, con gaps identificados. |
| **Segmento** | Tramo continuo de traza sin un gap mayor a 60 s. |
| **`elapsed`** | `t_lastAccepted - t_firstAccepted`, en segundos, usando tiempo monotónico. |
| **`moving`** | Suma de intervalos confirmados en estado `MOVING`, con transiciones backdateadas. |
| **`stopped`** | `elapsed - moving`. Incluye paradas reales **y** tiempo sin evidencia (gaps). No significa "quieto confirmado". |
| **Gap** | Intervalo entre dos fixes aceptados consecutivos mayor al intervalo nominal de muestreo, o un punto sin velocidad utilizable. |
| **Cota inferior de distancia** | Distancia calculada sin acumular en gaps >5 s ni en puntos rechazados; el valor real es mayor o igual. |

---

## Decisión

### 1. Modelo `TrackPoint` (dominio puro, sin Android)

`TrackPoint` vive en `domain/` y no importa `android.*`. Es inmutable. El adaptador de `sensor/` construye el `TrackPoint` a partir de `android.location.Location` y de los sensores inerciales.

| Campo | Tipo Kotlin | Unidad | Nulo | Origen / notas |
|---|---|---|---|---|
| `timestampMonotonicNanos` | `Long` | ns | no | `Location.getElapsedRealtimeNanos()` / `SystemClock.elapsedRealtimeNanos()`. **Único** campo de orden. Nunca `System.currentTimeMillis()` para ordenar. |
| `lat` | `Double` | grados decimales | no | WGS84. |
| `lon` | `Double` | grados decimales | no | WGS84. |
| `altitudeEllipsoidM` | `Double` | m | no | Altura elipsoidal WGS84 (`Location.getAltitude()`). |
| `altitudeMslM` | `Double?` | m | sí | Altura sobre el nivel del mar, si el proveedor/API la expone. No asumir que existe. |
| `accuracyH` | `Double` | m | no | `Location.getAccuracy()`. Radio al percentil 68, no 95%. |
| `accuracyV` | `Double` | m | no | `Location.getVerticalAccuracyMeters()` si existe; `NaN` + flag si no. |
| `speedMps` | `Double?` | m/s | sí | Doppler de `Location.getSpeed()`; `null` si no hay velocidad usable. En v1 NO hay fallback por ventana. |
| `speedAccuracy` | `Double` | m/s | no | `Location.getSpeedAccuracyMetersPerSecond()` si existe; `NaN` + flag si no. |
| `pressureHpa` | `Double?` | hPa | sí | Barómetro. Solo tendencia relativa; no se usa para segmentar. |
| `accelNormStd` | `Double?` | m/s² | sí | Desviación estándar de la magnitud de aceleración en ventana de 3 s. Se usa como veto. |
| `flags` | `Set<TrackPointFlag>` | — | no | Ver tabla de flags. |

**Contrato de construcción:** el adaptador emite el `TrackPoint` con `speedMps = null` si no hay Doppler; el punto se trata como **gap**, no como velocidad cero. Esto evita confundir "no sé" con "está quieto". La altitud **no** es requisito para segmentar: si el proveedor no la reporta, se usa `NaN` y el punto sigue siendo válido para moving/distancia; el desnivel queda fuera de alcance.

#### Flags de `TrackPoint`

| Flag | Significado | Tipo |
|---|---|---|
| `LOW_QUALITY` | `accuracyH` en el rango 15–30 m. El punto se usa con cautela; no se descarta. | Decisión de ingeniería |
| `INTERPOLATED` | Punto sintético insertado en un gap ≤5 s. No es una medición. | Decisión de ingeniería |
| `SPEED_DERIVED` | `speedMps` calculada por desplazamiento en ventana 3–5 s, no por Doppler. **No se usa en v1:** solo Doppler. | Decisión de ingeniería |
| `SPEED_ACCURACY_UNKNOWN` | `speedAccuracy` no reportada (`NaN`). | Decisión de ingeniería |
| `VERTICAL_ACCURACY_UNKNOWN` | `accuracyV` no reportada (`NaN`). | Decisión de ingeniería |
| `SEGMENT_BREAK_BEFORE` | El punto inicia un nuevo segmento después de un gap >60 s. | Decisión de ingeniería |
| `TELEPORT_REJECTED` | Marca de auditoría en la traza cruda del punto rechazado por teleport; no entra a la traza limpia. | Decisión de ingeniería |
| `STATE_UNCERTAIN` | El estado moving/stopped se mantuvo por ausencia de velocidad/acelerómetro durante un gap. | Decisión de ingeniería |

**Sobre `NaN` en campos no nulos:** se usa como sentinel de "no disponible" para no cambiar la aridad del modelo. Si en la implementación se prefiere null-safety estricta, se puede migrar a `Double?`; el flag es la fuente de verdad de la ausencia. Es un detalle de implementación, no un cambio de contrato conceptual.

**Sobre el reloj de pared:** `TrackPoint` no incluye wall clock. La exportación GPX necesita timestamps UTC; se guarda un ancla de reloj de pared a nivel de ride y se combina con `timestampMonotonicNanos` solo en el adaptador de exportación. Ordenar o segmentar con wall clock es un error: NTP puede saltar hacia atrás o hacia adelante.

### 2. Limpieza de la traza

El pipeline es determinista y se ejecuta en `domain/`, sobre la lista de `TrackPoint`:

| Paso | Regla | Acción | Tipo |
|---|---|---|---|
| 1. Orden | Ordenar por `timestampMonotonicNanos` ascendente. Descartar puntos con `Δt ≤ 0` respecto del anterior. | Traza estrictamente creciente en tiempo. | Decisión de ingeniería |
| 2. Accuracy | `accuracyH > 30 m` | Descartar el punto. El hueco resultante se evalúa como gap. | Decisión de ingeniería |
| 3. Calidad baja | `15 m ≤ accuracyH ≤ 30 m` | Mantener, marcar `LOW_QUALITY`. | Decisión de ingeniería |
| 4. Teleport | Velocidad implícita > 25 m/s en un paso, o > 60 km/h sostenido > 5 s sin Doppler que lo confirme | Rechazar el punto o la ventana; marcar `TELEPORT_REJECTED`; reevaluar como gap. | Decisión de ingeniería |
| 5. Gaps | Ver tabla de gaps. | Interpolar, no acumular o cortar segmento. | Decisión de ingeniería |
| 6. Interpolación | Gap ≤5 s | Insertar posición interpolada linealmente en lat/lon, marcar `INTERPOLATED`. | Decisión de ingeniería |

**Detalle del teleport:**

- La velocidad implícita se calcula como distancia geodésica / `Δt` entre dos puntos aceptados consecutivos.
- Un salto de más de 25 m/s (90 km/h) en un solo paso es físicamente posible en un descenso de carretera, pero es indistinguible de un salto de multipath. Se rechaza el punto nuevo, no el anterior, y se reevalúa con el siguiente.
- La regla de 60 km/h sostenido >5 s se aplica **solo cuando la velocidad es derivada de posición** (sin Doppler). Si el Doppler reporta de forma consistente >60 km/h, el descenso es plausible y no se rechaza.
- Un teleport rechazado no desaparece de la auditoría: se cuenta en `quality.teleportsRejected`.

#### Gaps

| `Δt` entre fixes aceptados | Interpolación | Distancia | Estado moving/stopped |
|---|---|---|---|
| ≤5 s | Sí, lineal en lat/lon | Acumular geodésica entre los extremos reales | Continuo; no hay gap a efectos de estado |
| 5–60 s | No | **No acumular** | Mantener estado con acelerómetro; sin acelerómetro, mantener con `STATE_UNCERTAIN` |
| >60 s | No | **No acumular**; marcar cota inferior | Cortar segmento (`SEGMENT_BREAK_BEFORE`); el segmento nuevo inicia en `STOPPED` |

**Por qué no acumular distancia en gaps >5 s:** no hay evidencia de la trayectoria real. Un túnel, un ascensor o un estacionamiento subterráneo pueden ocultar un giro completo. La distancia acumulada sería una invención; es más honesto reportar una cota inferior.

**Por qué cortar a los 60 s:** más de un minuto sin posición es una discontinuidad de muestreo, no un gap de GPS normal. El corte hace que la traza exportada a GPX use `trkseg` separados y que las métricas no mezclen tramos sin continuidad.

### 3. Velocidad

| Fuente | Condición | Uso | Notas |
|---|---|---|---|
| **Doppler** — `Location.getSpeed()` | `hasSpeed()` es verdadero | **Primaria y única en v1** | m/s. `speedAccuracy` si está disponible. Es la fuente preferida porque no deriva posición. |
| **Desplazamiento en ventana 3–5 s** | — | **Descartado en v1** | Reemplazado por la regla de v1: solo Doppler. El fallback por desplazamiento se descarta por el jitter urbano (reproducido en tests). |
| **Distancia/tiempo entre fixes consecutivos a baja velocidad** | Cualquier caso | **Prohibido** | A 4 km/h se avanza 1.11 m/s; a 1 Hz, 1.11 m entre fixes. Con `accuracyH` de 5 m, el desplazamiento es ~4.5 veces menor que el radio de incertidumbre: el ruido domina el módulo y la dirección. |

**Nota v1 — velocidad solo Doppler:** en v1 la velocidad usada es únicamente `Location.getSpeed()` (Doppler). Si el fix no trae una velocidad finita y no negativa, se trata como gap/sin evidencia: el estado no cambia por ese sample y no se infiere velocidad por distancia/tiempo entre fixes consecutivos ni por ventana de desplazamiento. Un fix sin velocidad no se interpreta como velocidad cero. Esto reemplaza la mención anterior al fallback de 3–5 s: el jitter urbano lo vuelve poco confiable (reproducido en tests).

**Umbral de ruido:** la literatura de GPS reporta una precisión de velocidad Doppler del orden de 0.2 m/s al 95% en condiciones nominales (GPS.gov SPS 2020). Ese valor es una referencia de cielo abierto; en cañón urbano puede degradarse. Con `V_STOP = 0.4 m/s`, el ruido Doppler puede ser una fracción significativa del umbral, lo que justifica histéresis y temporizadores en la máquina de estados.

### 4. Máquina de estados moving/stopped

Estados: `MOVING` y `STOPPED`. No hay estado `UNKNOWN` persistente; la incertidumbre se expresa con `STATE_UNCERTAIN` y con la calidad del ride.

#### Parámetros

| Símbolo | Valor | Significado |
|---|---|---|
| `V_STOP` | 0.4 m/s | Umbral para iniciar candidato a parada. |
| `T_STOP` | 10 s | Tiempo mínimo bajo `V_STOP` para confirmar parada. |
| `V_GO` | 0.8 m/s | Umbral para iniciar candidato a reanudación. |
| `T_GO` | 3 s | Tiempo mínimo sobre `V_GO` para confirmar reanudación. |

La histéresis (`V_GO > V_STOP`) crea una banda muerta de 0.4–0.8 m/s: dentro de ella, el estado no cambia. Esto evita oscilaciones por ruido alrededor de un único umbral.

#### Transiciones

| Desde | Condición | Hacia | Timestamp registrado |
|---|---|---|---|
| `STOPPED` | `v ≥ V_GO` durante `T_GO` | `MOVING` | Primer sample que cruza `V_GO` |
| `STOPPED` | Desplazamiento neto ≥10 m en 10 s (override) | `MOVING` | Inicio de la ventana de override |
| `MOVING` | `v < V_STOP` durante `T_STOP`, sin veto de desplazamiento ni de acelerómetro | `STOPPED` | Primer sample bajo `V_STOP` |
| `MOVING` (candidato a parada) | Veto: ≥8 m netos en 20 s, o `σ(|a|) > 0.15 m/s²` en 3 s | `MOVING` (cancela candidato) | — |
| `MOVING` / `STOPPED` | Gap ≤60 s con acelerómetro que indica movimiento | `MOVING` | Inicio del gap, con `STATE_UNCERTAIN` |
| `MOVING` / `STOPPED` | Gap >60 s | Corte de segmento | Inicio del nuevo segmento en `STOPPED` |

**Estado inicial:** `STOPPED`. Un ride que empieza con el teléfono quieto o con deriva de GPS antes de pedalear no debe contar como moving. Se requiere `T_GO` o el override para entrar en `MOVING`.

#### Backdating de transiciones

Los temporizadores son de **confianza**, no de **contabilidad**. Por eso las transiciones se registran retroactivamente:

- Si se confirma una parada en `t_confirm`, el inicio de la parada es el **primer sample** de la ventana que estuvo bajo `V_STOP`, no `t_confirm`. El intervalo `[inicioParada, t_confirm)` se reclasifica de `MOVING` a `STOPPED`.
- Si se confirma una reanudación en `t_confirm`, el inicio del movimiento es el **primer sample** que superó `V_GO` (o el inicio de la ventana de override), no `t_confirm`. El intervalo `[inicioMovimiento, t_confirm)` se reclasifica de `STOPPED` a `MOVING`.

Los intervalos son semiabiertos `[inicio, fin)` y no se solapan. El sample de transición pertenece al estado nuevo. Así, `moving + stopped = elapsed` por construcción, y el retardo de confirmación no se cobra como tiempo en el estado equivocado.

**Caso de fin de ride:** si el ride termina durante un candidato a parada que todavía no alcanzó `T_STOP`, no se confirma la parada; ese tramo queda como `MOVING` (conservador para no inventar una parada). Si el ride termina dentro de una parada ya confirmada, se cierra al último sample.

#### Veto de desplazamiento (protege ascensos lentos)

Un ciclista a 4–6 km/h avanza 1.11–1.67 m/s. En 20 s recorre 22–33 m. Si se mira solo la velocidad instantánea, el ruido o un fix malo pueden hacer parecer que está parado. El veto exige:

> **≥8 m de desplazamiento neto en 20 s → no confirmar parada.**

8 m en 20 s equivale a 0.4 m/s de promedio, exactamente `V_STOP`. Un ascenso real a 4 km/h supera ampliamente ese desplazamiento y cancela el candidato. Una parada real en un semáforo, con deriva de GPS de pocos metros, no lo supera.

**Restricción de calidad:** el veto de desplazamiento solo se evalúa con puntos de `accuracyH ≤ 15 m` (calidad normal). Si no hay suficientes puntos buenos en la ventana, el veto no se aplica y se decide con velocidad/acelerómetro. Esto evita que la deriva de un cañón urbano (10–20 m) impida detectar una parada real.

**Trade-off:** el veto puede impedir confirmar una parada si el GPS deriva más de 8 m en 20 s. Es el precio de no perder ascensos lentos. La matriz de validación incluye "deriva en parado" para medir este caso.

#### Veto de acelerómetro

> **`σ(|a|) > 0.15 m/s²` en una ventana de 3 s → no confirmar parada.**

Se usa la **magnitud** de la aceleración, no los ejes, para que el veto sea invariante a la orientación del teléfono. La gravedad es un offset constante y no afecta la desviación estándar. Un teléfono quieto en un soporte rígido tiene `σ` bajo; pedalear, balancearse o empujar la bici produce vibración y cambios de magnitud por encima del umbral.

**Limitaciones:**

- El umbral depende del dispositivo, del montaje y del terreno. 0.15 m/s² es una decisión de ingeniería inicial, no un valor validado en todos los teléfonos.
- Si el acelerómetro no está disponible, el veto no se aplica; la segmentación queda a cargo de velocidad/desplazamiento.
- El veto es conservador: prefiere mantener `MOVING` antes que confirmar una parada falsa. Eso puede sobreestimar `moving` en terreno muy rugoso; se reporta en la calidad del ride.

#### Override de reanudación

> **≥10 m netos en 10 s → reanudar, aunque la velocidad instantánea no haya superado `V_GO` durante `T_GO`.**

10 m en 10 s equivale a 1 m/s de promedio. Sirve para el caso en que el GPS tarda en recuperar velocidad después de una parada o en que el Doppler tiene lag. También se evalúa solo con puntos de `accuracyH ≤ 15 m`.

#### Gaps y estado

- **Gap ≤60 s con acelerómetro:** si `σ(|a|) > 0.15 m/s²`, el estado se mantiene o pasa a `MOVING` con `STATE_UNCERTAIN`; si `σ` es bajo, se mantiene el estado actual. La distancia **no** se acumula durante el gap.
- **Gap ≤60 s sin acelerómetro:** se mantiene el estado anterior con `STATE_UNCERTAIN`. Es una elección conservadora para no cortar el tiempo en movimiento en un túnel; la incertidumbre se reporta.
- **Gap >60 s:** se corta el segmento. El segmento nuevo inicia en `STOPPED` y necesita evidencia para volver a `MOVING`.

#### Por qué no alcanza con una sola señal

**Solo desplazamiento (posición):** falla en curvas de herradura y zigzag. En un giro de 180° con radio pequeño, el ciclista puede recorrer 10–20 m de trayectoria real y terminar a pocos metros del punto de partida. Una ventana que mida solo desplazamiento neto concluye "no se movió" aunque la velocidad sea 5 m/s. Lo mismo pasa en un switchback de montaña: la trayectoria se dobla sobre sí misma y el vector neto se acorta. La distancia geodésica entre extremos no es la distancia recorrida.

**Solo velocidad instantánea:** falla por ruido. A baja velocidad, el Doppler y el multipath urbano producen picos y valles; un único sample bajo `V_STOP` no es una parada, y un único sample sobre `V_GO` no es una reanudación. Sin temporizadores ni histéresis, el estado oscila y el tiempo se reparte mal. Además, la velocidad instantánea no distingue "quieto" de "GPS sin actualizar".

**Solución:** velocidad como señal primaria, temporizadores para confianza, backdating para contabilidad, veto de desplazamiento para ascensos lentos, veto de acelerómetro para vibración, y override de desplazamiento para reanudaciones con lag. Ninguna señal sola es suficiente.

### 5. Distancia

| Regla | Decisión |
|---|---|
| Modelo | Geodésica sobre elipsoide WGS84. |
| Implementación de producción | `Location.distanceBetween` en el adaptador Android. |
| Implementación de dominio | El acumulador de `domain/` consume un puerto `GeodesicDistance` inyectado; no importa `android.*`. |
| Tipo de acumulador | `Double` (nunca `Float`). |
| Cuándo acumular | Solo en estado `MOVING` y solo para pares de puntos aceptados con `Δt ≤ 5 s`. |
| Mínimo por paso | **Ninguno** (0 m) mientras se mueve. |
| Gaps 5–60 s | No acumular. Marcar cota inferior. |
| Gaps >60 s | No acumular. Cortar segmento. Marcar cota inferior. |
| Puntos `LOW_QUALITY` | Se incluyen para continuidad y estado; su contribución se reporta en `quality.lowQualityFraction`. No se excluyen por defecto. |
| Redondeo | Solo en UI/exportación; el dominio conserva `Double`. |

**Sobre `Location.distanceBetween` y la pureza del dominio:** `Location.distanceBetween` es una API estática de Android y no puede llamarse desde `domain/` (AGENTS.md §3). La solución es un puerto:

```text
domain:  fun interface GeodesicDistance { fun meters(lat1, lon1, lat2, lon2): Double }
sensor/: implementación que delega en Location.distanceBetween (WGS84)
tests/:  implementación JVM pura de referencia (Karney) o fixtures precalculados
```

El algoritmo de referencia es Karney 2013, que es la base de la geodesia WGS84 usada por Android. La implementación JVM pura se elige en el slice de implementación; **no se agrega una dependencia nueva sin pasar por `security-reviewer`**.

**Por qué no hay mínimo de 3 m mientras se mueve:** a 4 km/h y 1 Hz, cada paso es de 1.11 m. Un filtro de "mínimo 3 m" descartaría aproximadamente 2 de cada 3 pasos y subestimaría sistemáticamente la distancia en ascensos lentos y tráfico denso. El mínimo de 3 m es una heurística de stop detection, no un filtro de distancia. El ruido se controla con el filtro de accuracy, el rechazo de teleports y la regla de acumular solo en `MOVING`.

**Cota inferior:** si hubo al menos un gap >5 s, un teleport rechazado o puntos descartados por `accuracyH > 30 m`, `distanceIsLowerBound = true`. La UI debe mostrar "≥ X km" o "distancia mínima", no un número que parezca exacto.

### 6. Agregados del ride

| Métrica | Definición | Guardas / notas |
|---|---|---|
| `elapsed` | `t_lastAccepted - t_firstAccepted` | Si hay <2 puntos aceptados, no hay ride: `InsufficientData`. |
| `moving` | Suma de intervalos `MOVING`, con backdating | Si no hay intervalos, 0. |
| `stopped` | `elapsed - moving` | Incluye gaps y tiempo sin evidencia; la UI lo etiqueta como "detenido / sin datos" cuando corresponde. |
| `avgMovingSpeed` | `distance / moving` | Si `moving = 0`, `null` (no dividir por cero). |
| `avgSpeed` | `distance / elapsed` | Si `elapsed = 0`, `null`. |
| `maxSpeed` | Percentil 95 de la velocidad suavizada en ventana 3–5 s, sobre samples `MOVING` | **No** es el máximo crudo. Si hay <20 samples, se reporta con `maxSpeedConfidence = LOW`. |
| `stopCount` | Cantidad de paradas confirmadas | Cada una dura al menos `T_STOP` por construcción. |
| `stopDurations` | Lista de duraciones de paradas confirmadas | Si una parada sigue abierta al final, se cierra en el último sample. |
| `distanceM` | Suma de distancias geodésicas en `MOVING` | `Double`; cota inferior si hubo gaps. |
| `distanceIsLowerBound` | Booleano | Ver §5. |
| `movingTimeUncertain` | Booleano | Verdadero si hubo gaps >5 s sin acelerómetro o con `STATE_UNCERTAIN`. |

**Inicio de `elapsed`:** por defecto, `elapsed` se mide desde el primer punto aceptado hasta el último. Si el servicio de grabación tiene un timestamp de inicio anterior al primer fix (p. ej. el usuario presionó "start" antes de que el GPS fijara), ese timestamp puede pasarse como inicio explícito; el dominio no lo inventa. Sin ese dato, `elapsed` es reproducible desde la traza y no incluye el tiempo previo al primer fix. Es una decisión de ingeniería; la UI no debe prometer que `elapsed` coincide con el tiempo de reloj de la sesión si el fix tardó.

**Suavizado de `maxSpeed`:**

- Filtro de mediana móvil centrada de 5 s (mínimo 3 samples). Si la cadencia de muestreo es menor a 1 Hz, se usan 3 samples.
- Se calcula el percentil 95 sobre los samples en `MOVING`, excluyendo `LOW_QUALITY` y samples sin velocidad utilizable.
- Se prefiere Doppler; los samples `SPEED_DERIVED` se incluyen pero se reportan en la calidad.
- El nombre `maxSpeed` se conserva por compatibilidad con el modelo de ride, pero la UI debe rotularlo como **"velocidad pico (p95)"**, no como "máxima".

**Por qué p95 y no máximo:** un único spike de GPS puede duplicar la velocidad máxima reportada. El percentil 95 es robusto a outliers y sigue siendo representativo del pico sostenido. No es una verdad física: es un estimador declarado.

**Ejemplo de lectura de la UI:**

| Situación | Texto permitido |
|---|---|
| Ride normal | "Velocidad media en movimiento: 18.4 km/h · Velocidad total: 15.1 km/h" |
| Con gaps | "Distancia: ≥ 12.3 km (datos incompletos)" |
| `movingTimeUncertain` | "Tiempo en movimiento estimado: 48 min" |
| `maxSpeedConfidence = LOW` | "Velocidad pico (p95): 31.2 km/h · pocos datos" |

### 7. Persistencia y versión del modelo

Por ride se persiste, además de las métricas:

| Campo | Tipo | Propósito |
|---|---|---|
| `modelVersion` | `String` (p. ej. `segmentation-v1`) | Reproducibilidad y auditoría. |
| `parameters` | `Map<String, Double>` o JSON canónico | Valores exactos usados, no solo el default. |
| `parametersHash` | `String` | Detectar cambios de parámetros sin diff manual. |
| `acceptedPoints` / `receivedPoints` | `Int` | Cobertura GPS. |
| `gapCount`, `gapSeconds`, `maxGapSeconds` | `Int` / `Double` | Cota inferior y calidad. |
| `lowQualityFraction` | `Double` | Proporción de puntos 15–30 m. |
| `teleportsRejected` | `Int` | Auditoría de limpieza. |
| `accelAvailable` | `Boolean` | Confiabilidad del veto de acelerómetro. |
| `distanceIsLowerBound` | `Boolean` | Lectura honesta de distancia. |
| `movingTimeUncertain` | `Boolean` | Lectura honesta de tiempo. |
| `maxSpeedConfidence` | enum (`HIGH`/`LOW`) | Estabilidad del p95. |

**Regla:** un cambio de parámetro o de algoritmo que altere `moving`, `distance` o `maxSpeed` **debe** bumpear `modelVersion` y actualizar este ADR. No se cambia un umbral en producción sin dejar rastro.

---

## Tabla de parámetros

Cada fila está marcada como **fuente** o **decisión de ingeniería**. Una decisión de ingeniería puede estar anclada en una fuente, pero el valor concreto no se atribuye a la fuente.

| Parámetro | Valor | Tipo | Justificación / fuente |
|---|---|---|---|
| `ACCURACY_REJECT_H` | 30 m | **decisión de ingeniería** | Descarta fixes de cañón urbano severo. No es una cota del 95%; `getAccuracy()` es percentil 68 [S4]. |
| `ACCURACY_LOW_H` | 15 m | **decisión de ingeniería** | Separa calidad normal de baja sin descartar datos útiles. |
| `ACCURACY_PERCENTILE` | 68% | **fuente** | `Location.getAccuracy()` de Android define el radio al percentil 68, no al 95% [S4]. |
| `TELEPORT_STEP_SPEED` | 25 m/s (90 km/h) | **decisión de ingeniería** | Un salto de un solo paso por encima de esto es más probablemente multipath que ciclismo. |
| `TELEPORT_SUSTAINED_SPEED` | 60 km/h (16.67 m/s) | **decisión de ingeniería** | Umbral de plausibilidad para velocidad derivada de posición; se desactiva si el Doppler confirma el descenso. |
| `TELEPORT_SUSTAINED_WINDOW` | 5 s | **decisión de ingeniería** | Ventana mínima para distinguir un spike de un tramo real. |
| `GAP_INTERPOLATE_MAX` | 5 s | **decisión de ingeniería** | A velocidades de ciclismo, 5 s es un hueco pequeño; la interpolación lineal es aceptable. |
| `GAP_NO_DISTANCE_MAX` | 60 s | **decisión de ingeniería** | Por encima de 5 s no hay evidencia de trayectoria; no se inventa distancia. |
| `GAP_SEGMENT_BREAK` | >60 s | **decisión de ingeniería** | Discontinuidad de muestreo; se refleja como `trkseg` separado en GPX [S5]. |
| `SPEED_WINDOW_MIN` | 3 s | **diferido en v1** | Referencia de diseño para un futuro fallback por desplazamiento; en v1 no se usa (solo Doppler). |
| `SPEED_WINDOW_MAX` | 5 s | **diferido en v1** | Referencia de diseño para un futuro fallback; no se usa en v1 (solo Doppler). |
| `V_STOP` | 0.4 m/s | **decisión de ingeniería** | Dentro del rango 0.3–1.9 m/s de la literatura; anclado en 0.3 m/s de CycleTrajectory [S6]. Conservador para no marcar paradas falsas. |
| `T_STOP` | 10 s | **decisión de ingeniería** | **Por debajo** del rango 15–120 s de la literatura. Se elige para capturar semáforos cortos y se compensa con los vetos de desplazamiento y acelerómetro. Requiere validación explícita. |
| `V_GO` | 0.8 m/s | **decisión de ingeniería** | Histéresis 2× `V_STOP`; evita oscilación en la banda 0.4–0.8 m/s. |
| `T_GO` | 3 s | **decisión de ingeniería** | Reanudación rápida para no subestimar `moving`; es un temporizador de reanudación, no una ventana de stop. |
| `STOP_DISPLACEMENT_VETO` | 8 m / 20 s | **decisión de ingeniería** | 0.4 m/s promedio. Protege ascensos a 4–6 km/h (22–33 m en 20 s). Solo con puntos `accuracyH ≤ 15 m`. |
| `STOP_ACCEL_SIGMA_VETO` | 0.15 m/s² / 3 s | **decisión de ingeniería** | Por encima del ruido de un teléfono quieto y por debajo de la vibración típica de pedalear; depende del dispositivo. |
| `RESUME_DISPLACEMENT_OVERRIDE` | 10 m / 10 s | **decisión de ingeniería** | 1 m/s promedio; recupera reanudaciones con lag de Doppler. Solo con puntos `accuracyH ≤ 15 m`. |
| `DISTANCE_MIN_WHILE_MOVING` | 0 m (sin mínimo) | **decisión de ingeniería** | Un mínimo de 3 m a 4 km/h y 1 Hz descartaría ~2/3 de los pasos. |
| `MAX_SPEED_PERCENTILE` | 95 | **decisión de ingeniería** | Robusto a spikes; no es un máximo físico. |
| `MAX_SPEED_SMOOTH_WINDOW` | 5 s (mín. 3 samples) | **decisión de ingeniería** | Mediana móvil dentro de la banda 3–5 s. |
| `MAX_SPEED_MIN_SAMPLES` | 20 | **decisión de ingeniería** | Por debajo de 20 samples el p95 es inestable; se marca `LOW`. |
| `INITIAL_STATE` | `STOPPED` | **decisión de ingeniería** | Evita contar deriva pre-ride como movimiento. |
| `DOPPLER_SPEED_ACCURACY` | ≤0.2 m/s al 95% | **fuente** | Cota de referencia de velocidad Doppler en condiciones nominales (GPS.gov SPS 2020) [S3]. |
| `GEODESIC_MODEL` | WGS84 (elipsoide) | **fuente** | `Location.distanceBetween` usa WGS84; Karney 2013 es la referencia algorítmica [S4][S10]. |
| `MOVING_TIME_DEFINITION` | Suma de intervalos `MOVING` confirmados | **fuente** | Definición, no umbral: Strava separa moving time de elapsed time [S1]; Garmin FIT distingue `total_timer_time` de `total_elapsed_time` [S2]. |
| `STOP_THRESHOLD_LITERATURE` | 0.3–1.9 m/s | **fuente** | Rango heterogéneo reportado en la literatura de stop/trip detection [S6][S7]. |
| `STOP_WINDOW_LITERATURE` | 15–120 s | **fuente** | Rango heterogéneo de ventanas de confirmación [S6][S7]. |
| `CYCLETRAJECTORY_STOP` | 0.3 m/s / 20 s | **fuente** | Criterio reportado por Wang et al. 2024 (CycleTrajectory); confirmar sección de métodos antes de fijar el default [S6]. |

**Nota sobre los rangos de literatura:** los rangos 0.3–1.9 m/s y 15–120 s se toman de la revisión documentada en el brief de este ADR. La atribución exacta paper-por-paper queda como TODO; no se usan como estándar ni como umbral de aceptación, solo como contexto para justificar que el default es una decisión de ingeniería.

---

## Validación

La validación se hace en **JVM puro**, con trazas sintéticas generadas en `domain/` o en `testFixtures`. El ground truth es la traza generadora, no un dispositivo: así se puede medir el error de la segmentación sin confundirlo con el error del sensor.

### Matriz de trazas sintéticas

| # | Escenario | Traza sintética | Qué prueba | Métricas |
|---|---|---|---|---|
| 1 | Semáforo 30 s | 300 m a 6 m/s, 30 s a 0 m/s, 300 m a 6 m/s | Detección de una parada y backdating | `stopCount = 1`; `moving ≈ elapsed − 30 s`; distancia ≈ 600 m |
| 2 | Ascenso lento 4–6 km/h | 5 min a 1.11–1.67 m/s, sin paradas | El veto de desplazamiento no inventa una parada | `stopCount = 0`; `moving ≈ elapsed`; distancia dentro del error esperado |
| 3 | Túnel 45 s | 2 min moving, 45 s sin fixes, 2 min moving | Gap 5–60 s: no acumular distancia; mantener estado con acelerómetro | `distanceIsLowerBound = true`; `moving` mantenido si el acelerómetro indica movimiento |
| 4 | Deriva en parado | 5 min a 0 m/s con jitter de ±10 m | El filtro de accuracy y la máquina de estados no acumulan distancia falsa | `stopCount = 1`; distancia ≈ 0; `moving ≈ 0` |
| 5 | Altitud ruidosa | Horizontal normal; altitud con ruido ±10 m | La altitud no altera la segmentación | `moving`/`distance` iguales al caso sin ruido; elevación fuera de alcance |
| 6 | Deriva barométrica | 10 min parado; presión deriva 2 hPa | El barómetro no se usa para segmentar | `stopCount = 1`; sin cambio de estado por presión |
| 7 | Teleport | Un fix a 1 km de distancia 1 s después | Rechazo de teleport y no inflación de distancia | `teleportsRejected = 1`; distancia no salta 1 km |
| 8 | Accuracy 50 m | Todos los puntos con `accuracyH = 50 m` | Datos insuficientes, no un ride inventado | `InsufficientData` o traza vacía; UI "sin datos suficientes" |
| 9 | Curva de herradura | Giro de 180° con radio 5 m a 5 m/s | El estado debe seguir `MOVING`; comparar contra baseline de solo desplazamiento | `moving ≈ elapsed`; el baseline de desplazamiento falla (falso stop) |
| 10 | Ascensor | 30 s de movimiento vertical 20 m; horizontal ≈ 0 | Movimiento vertical no es ciclismo; no acumular distancia horizontal | `STOPPED`; distancia ≈ 0; la presión/altitud cambia pero no el estado |

### Métricas

| Métrica | Definición | Qué mide |
|---|---|---|
| **MAE de distancia** | `mean(|distance_pred − distance_true|)` en metros | Error absoluto de distancia. |
| **MAPE de distancia** | `mean(|distance_pred − distance_true| / distance_true) × 100` | Error relativo; útil para comparar trazas de distinta longitud. |
| **MAE de moving time** | `mean(|moving_pred − moving_true|)` en segundos | Error de tiempo en movimiento. |
| **Precision de stops** | `TP / (TP + FP)` | Proporción de paradas detectadas que son reales. |
| **Recall de stops** | `TP / (TP + FN)` | Proporción de paradas reales detectadas. |
| **F1 de stops** | `2 × precision × recall / (precision + recall)` | Balance entre falsos positivos y falsos negativos. |
| **Bland–Altman** | Diferencia vs. media para distancia y moving time; reportar sesgo y límites de acuerdo al 95% | Detecta sesgo sistemático, no solo error absoluto. |
| **MAE de duración de stop** | `mean(|stop_duration_pred − stop_duration_true|)` en segundos | Calidad del backdating y de la reanudación. |
| **Error de `maxSpeed`** | `|p95_pred − p95_true|` en m/s | Calidad del suavizado y del percentil. |

**Regla de matching para stops:** una parada detectada es **TP** si solapa al menos el 50% de su duración con una parada ground-truth. Es **FP** si no solapa con ninguna. Cada parada ground-truth sin detección es **FN**. La regla del 50% es una definición de métrica, no un umbral de aceptación del producto.

**Umbrales de aceptación:** este ADR **no fija** valores de MAE, MAPE, F1 ni límites de Bland–Altman. Se derivan de la matriz, se revisan con el usuario y se acuerdan antes de implementar. Inventar un "F1 ≥ 0.9" sin datos reales sería exactamente el tipo de constante sin fuente que AGENTS.md §6 prohíbe. Ver **Pendientes**.

---

## Consecuencias

### Producto y UI

- **`moving` es una definición, no una verdad absoluta.** La app debe mostrar `avgMovingSpeed` y `avgSpeed` con etiquetas distintas. Nunca un único "velocidad promedio" sin contexto.
- **La UI muestra ambas velocidades** en el resumen del ride: "en movimiento" y "total (incluye detenciones)".
- **Si hay gaps, la distancia es cota inferior.** La UI debe decir "≥ X km" o "distancia mínima" y explicar que faltan datos. No se redondea hacia arriba para que parezca exacta.
- **Si `movingTimeUncertain`,** el tiempo en movimiento se rotula como estimado.
- **`maxSpeed` se muestra como p95,** no como máximo. La etiqueta "velocidad pico (p95)" es obligatoria.
- **No se promete cadencia ni potencia desde el acelerómetro.** El acelerómetro solo se usa como veto de parada; no alcanza para cadencia ni para vatios. Cualquier feature futura de cadencia/potencia requiere sensor dedicado y un ADR nuevo.

### Integración con ADR-001

- El motor calórico de ADR-001 (nivel 3, Compendium) debe consumir **`moving` y `avgMovingSpeed`** para el componente de actividad, y **`elapsed`** para el componente de reposo/BMR. Mezclar ambos en un solo tiempo distorsiona la estimación.
- Este ADR no cambia las fórmulas de ADR-001; solo define los inputs de tiempo y velocidad. Cualquier cambio de fórmula sigue requiriendo ADR y fuente.
- La incertidumbre de segmentación se propaga a la incertidumbre calórica; la UI declara las bandas de ADR-001 (±30–40% sin perfil, ±20–30% con perfil), pero no debe ocultar que un ride con muchos gaps tiene además distancia/tiempo inciertos.

### Persistencia y auditoría

- Se guarda `modelVersion`, `parameters`, `parametersHash` y la calidad por ride (§7). Sin eso, un cambio de umbral es irreproducible.
- Los parámetros se persisten como valores efectivos, no solo como "default de la versión". Un ride viejo debe poder recalcularse con los parámetros con los que se grabó.
- Un cambio de parámetro o algoritmo que altere las métricas **bumpea `modelVersion`** y actualiza este ADR.

### GPX y exportación

- Los gaps >60 s se exportan como `trkseg` separados, siguiendo la semántica de GPX 1.1 [S5]. No se une la traza a través de un túnel o un corte.
- GPX usa timestamps UTC de reloj de pared; la segmentación usa tiempo monotónico. El ancla de wall clock se guarda a nivel de ride y se aplica solo en exportación.
- El GPX no necesita incluir la máquina de estados; sí puede incluir `modelVersion` en metadata/extensions para trazabilidad.

### Rendimiento y arquitectura

- La máquina de estados es O(n) y puede ejecutarse en streaming (online) o sobre la traza completa (offline). La versión v1 puede ser offline para simplificar; el contrato no impide el streaming.
- `timestampMonotonicNanos` y `Double` evitan los dos errores clásicos: ordenar con wall clock y acumular distancia en `Float`.
- La distancia geodésica se inyecta como puerto; `domain/` sigue siendo JVM puro y testeable sin Robolectric (AGENTS.md §3, §8).

### Privacidad

- Este ADR no agrega permisos, ni telemetría, ni envío de datos. La traza sigue siendo local-first (AGENTS.md §7).
- La calidad del ride y los parámetros son metadata local; si se exportan a Hermes, siguen las reglas de data minimization de ADR-003.

### Riesgos conocidos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| `T_STOP = 10 s` está por debajo del rango 15–120 s de la literatura | Más paradas detectadas que otras apps | Validar con la matriz y con rides reales; ajustar si el F1 empeora. |
| `STOP_ACCEL_SIGMA_VETO = 0.15 m/s²` depende del dispositivo | Falsos moving en teléfonos ruidosos o falsos stopped en montajes flojos | Calibrar por dispositivo; reportar `accelAvailable`; permitir override manual futuro (fuera de v1). |
| Deriva de GPS en parado >8 m/20 s | No se confirma una parada real | Restringir el veto a `accuracyH ≤ 15 m`; validar "deriva en parado". |
| Gap de túnel sin acelerómetro | `moving` incierto | `STATE_UNCERTAIN` y `movingTimeUncertain`; no acumular distancia. |
| `maxSpeed` p95 con pocos samples | Pico inestable | `maxSpeedConfidence = LOW`; no mostrar como máximo. |
| Puntos `LOW_QUALITY` en distancia | Ruido acumulado | Reportar `lowQualityFraction`; el filtro de 30 m y el teleport limitan el daño. |

---

## Alternativas descartadas

### Solo desplazamiento neto (posición)

**Descartada.** Falla en curvas de herradura y zigzag: el desplazamiento neto puede ser casi cero mientras el ciclista se mueve a 5 m/s. También confunde deriva de GPS con movimiento. Es útil como veto complementario, no como señal primaria.

### Solo velocidad instantánea

**Descartada.** El ruido de Doppler/multipath a baja velocidad produce oscilaciones. Sin temporizadores ni histéresis, un solo sample puede cambiar el estado y arruinar `moving`. La solución adoptada usa velocidad + temporizadores + vetos.

### Umbral de distancia mínima de 3 m entre puntos

**Descartada.** A 4 km/h y 1 Hz, cada paso es 1.11 m; un mínimo de 3 m descarta la mayoría de los pasos y subestima la distancia en ascensos lentos. El mínimo de 3 m es una heurística de stop detection, no de distancia.

### Usar `elapsed` como velocidad promedio

**Descartada.** Los semáforos hunden la velocidad promedio y distorsionan la banda de velocidad del Compendium. Se reporta `elapsed` para el total, pero la velocidad de movimiento usa `moving`.

### Usar wall clock para ordenar o segmentar

**Descartada.** NTP puede saltar hacia atrás; un ride se ordenaría mal y la segmentación sería irreproducible. Se usa `elapsedRealtimeNanos`, que es monotónico.

### `maxSpeed` como máximo crudo

**Descartada.** Un solo spike de GPS puede duplicar el valor. Se usa p95 sobre velocidad suavizada y se declara la confianza.

### ML on-device para stop detection en v1

**Descartada.** No hay dataset etiquetado propio ni ground truth de paradas en ciclismo; entrenar un modelo sin eso solo agrega opacidad. Además, el problema se puede resolver con una máquina de estados auditable y parámetros versionados. Se puede reconsiderar si en el futuro hay datos etiquetados y una fuente de validación independiente.

### Usar solo `elapsed` para el motor calórico

**Descartada.** Ignora el componente de reposo durante las detenciones y mezcla dos definiciones de tiempo. ADR-001 ya separa el componente de actividad (MET) del componente sedentario (BMR); este ADR provee los inputs correctos para cada uno.

---

## Pendientes (TODO)

- [ ] **TODO:** definir con el usuario los umbrales de aceptación numéricos de la matriz sintética (MAE/MAPE de distancia, MAE de moving time, precision/recall/F1 de stops, límites de Bland–Altman). Este ADR no los inventa.
- [ ] **TODO:** verificar en la sección de métodos de Wang et al. 2024 (CycleTrajectory) el criterio exacto de 0.3 m/s / 20 s y la tabla de umbrales de la literatura. Ajustar la atribución si el paper usa otra definición.
- [ ] **TODO:** calibrar `STOP_ACCEL_SIGMA_VETO` (0.15 m/s²) en dispositivos reales. El ruido del acelerómetro depende del teléfono, del montaje y del terreno.
- [ ] **TODO:** elegir la implementación JVM pura de distancia geodésica (porte de Karney o fixtures precalculados). No agregar una dependencia nueva sin pasar por `security-reviewer`.
- [ ] **TODO:** definir el tratamiento de elevación/desnivel (fuera de alcance de este ADR). "Altitud ruidosa" y "deriva barométrica" solo se validan como no interferencia con la segmentación.
- [ ] **TODO:** decidir si un gap 5–60 s sin acelerómetro debe mantener el estado anterior o pasar a `STOPPED`. La propuesta de este ADR es mantener con `STATE_UNCERTAIN`; validar contra rides reales en túneles.
- [ ] **TODO:** definir el esquema exacto de `parametersHash` y el naming de `modelVersion`.
- [ ] **TODO:** definir dónde se guarda el ancla de wall clock para GPX y cómo se combina con `timestampMonotonicNanos` en exportación.
- [ ] **TODO:** validar la matriz sintética contra al menos un ride real anotado manualmente antes de aceptar el ADR.
- [ ] **TODO:** revisar si `NaN` como sentinel en `accuracyV`/`speedAccuracy` es aceptable o si se migra a `Double?` en la implementación.

---

## Fuentes

| ID | Fuente | Referencia exacta | Uso |
|---|---|---|---|
| S1 | Strava Support — "Moving Time, Speed, and Pace Calculations" | https://support.strava.com/en-us/articles/15401804-moving-time-speed-and-pace-calculations | Definición de moving time vs elapsed time. Solo definición, no umbral. |
| S2 | Garmin FIT SDK — Activity file types | https://developer.garmin.com/fit/file-types/activity/ | `total_elapsed_time` vs `total_timer_time` como definiciones de duración. Solo definición, no umbral. |
| S3 | GPS.gov — GPS Standard Positioning Service (SPS) Performance Standard, 5th ed., Apr 2020 | https://www.gps.gov/sites/default/files/2025-07/2020-SPS-performance-standard.pdf | Cota de referencia de precisión de velocidad Doppler (≤0.2 m/s al 95%). |
| S4 | AOSP — `frameworks/base/location/java/android/location/Location.java` | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/location/java/android/location/Location.java | `getAccuracy()` (percentil 68), `getSpeed()`, `hasSpeed()`, `getElapsedRealtimeNanos()`. Referencia complementaria: https://developer.android.com/reference/android/location/Location |
| S5 | GPX 1.1 Schema Documentation — TopoGrafix | https://www.topografix.com/GPX/1/1/ | WGS84, `trkseg` para tramos discontinuos, extensibilidad. |
| S6 | Wang, M., Haworth, J., Ilyankou, I., & Christie, N. (2024). CycleTrajectory: An End-to-End Pipeline for Enriching and Analyzing GPS Trajectories to Understand Cycling Behavior and Environment. arXiv:2406.10069. | https://arxiv.org/abs/2406.10069 · DOI: https://doi.org/10.48550/arXiv.2406.10069 | Criterio de stop 0.3 m/s / 20 s citado en el brief; confirmar sección de métodos. |
| S7 | Berjisian, E., & Bigazzi, A. (2022). Evaluation of methods to distinguish trips from activities in walking and cycling GPS data. Transportation Research Part C: Emerging Technologies, 137, 103588. | DOI: https://doi.org/10.1016/j.trc.2022.103588 | Comparación de métodos de detección de viajes/actividades y umbrales en datos GPS de ciclismo. |
| S8 | Yan, H., Maat, K., & van Wee, B. (2024). Cycling speed variation: a multilevel model of characteristics of cyclists, trips and route tracking points. Transportation, 52(5), 1857–1886. | DOI: https://doi.org/10.1007/s11116-024-10477-6 | Variabilidad de velocidad en ciclismo; contexto para no asumir una velocidad constante. |
| S9 | MovingPandas — `StopSplitter` / `TrajectoryStopDetector` | https://movingpandas.readthedocs.io/en/main/api/trajectorysplitter.html · https://movingpandas.readthedocs.io/en/main/api/trajectorystopdetector.html | Implementación de stops por diámetro máximo y duración mínima; referencia conceptual, no específica de ciclismo. |
| S10 | Karney, C. F. F. (2013). Algorithms for geodesics. Journal of Geodesy, 87(1), 43–55. | DOI: https://doi.org/10.1007/s00190-012-0578-z | Algoritmo de referencia para distancia geodésica sobre elipsoide WGS84. |
| S11 | Android Developers — `SystemClock.elapsedRealtimeNanos()` | https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos() | Reloj monotónico que incluye deep sleep; base de `timestampMonotonicNanos`. |

**Nota sobre fuentes:** las fuentes S1, S2 y S9 se usan **solo para definiciones** (moving time, timer time, stop detection), no para justificar umbrales numéricos. Los umbrales de este ADR son decisiones de ingeniería y así se marcan en la tabla de parámetros. Las fuentes S6–S8 son literatura de referencia; los valores exactos de sus métodos deben verificarse antes de atribuirles un número (ver **Pendientes**).


