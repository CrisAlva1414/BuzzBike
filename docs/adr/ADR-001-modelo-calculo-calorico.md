# ADR-001: Modelo de cálculo calórico v1

- **Estado:** Aceptado
- **Fecha:** 2026-10-02
- **Decisor:** Usuario (con realidad check del equipo de investigación)

---

## Context

BuzzBike debe calcular el gasto calórico de un ride usando el teléfono del usuario. Restricciones duras del proyecto:

- El usuario **no tiene** HR strap ni potenciómetro. Son pendientes futuros.
- El objetivo declarado por el usuario fue "cálculo exacto".
- El motor debe funcionar en v1 sin sensores externos, pero la arquitectura debe permitir enchufarlos después sin reescribir el domain.

**Reality check documentado (no es opinión, es evidencia):**

- Los relojes comerciales tienen un error de 9–43% vs calorimetría directa (Wallen 2016, PLOS One, DOI: `10.1371/journal.pone.0154420`).
- Sin sensores externos, el techo realista de precisión es **±25–35%**.
- La dependencia física real de la energía en ciclismo es **potencia × tiempo** (vatios → julios), **no distancia**. A 150 W vs 250 W constante, la distancia cambia pero el gasto calórico es el mismo. Un ride de 20 km en plano a 30 km/h y uno de 40 km a 15 km/h con la misma potencia gastan **lo mismo**.

Por lo tanto, "exacto" no es un objetivo alcanzable con un teléfono. El objetivo honesto es: **el mejor estimador disponible y auditable, con la incertidumbre declarada en la UI**.

---

## Decision

Implementar un **motor de cascada de 3 niveles**, en orden de disponibilidad y precisión:

| Nivel | Modelo | Condición de activación | Precisión esperada |
|---|---|---|---|
| 1 | **ACSM** — `VO2 = 1.8 × (W × 6.12 / masaKg) + 7` | Hay potenciómetro (BLE `0x1818`) | ±5–10% |
| 2 | **Keytel 2005** — ecuación pública con/sin VO2max | Hay HR strap (BLE `0x180D`) | r=0.857 sin VO2max |
| 3 | **Compendium of Physical Activities 2024** — METs por bandas de velocidad GPS | Sin HR ni potencia (caso v1) | ±25–35% |

**BMR de reposo:** Mifflin-St Jeor, usado como base para el componente sedentario del ride.

**Reglas de presentación en UI (obligatorias):**

1. La UI **DEBE** mostrar el intervalo de incertidumbre estimado del modelo usado.
2. La UI **DEBE** indicar qué modelo de la cascada se aplicó (p. ej. "estimado por velocidad (Compendium)").
3. **Nunca** presentar un número único como exacto. No decir "250 kcal" a secas; decir "≈250 kcal (±30%, modelo Compendium)".

**Reglas de código (obligatorias, ver AGENTS.md §6):**

- Toda constante o fórmula de este ADR se implementa en `domain/` con KDoc que cite la fuente.
- Cualquier cambio posterior a estas fórmulas requiere un ADR nuevo o la actualización de este, con la fuente citada.
- Prohibido inventar papers o constantes. Si falta fuente → `TODO:` + discusión, no código.

---

## Consecuencias

- **El usuario ve honestidad, no magia.** La app declara su incertidumbre en vez de fingir precisión. Esto es una feature de confianza, no un defecto.
- **En v1 (sin sensores), el nivel 3 es el camino crítico.** El code review del motor calórico se centra en que las bandas de velocidad del Compendium estén correctas y citadas.
- **La arquitectura queda abierta para sensores futuros.** Cuando el usuario consiga un HR strap, se activa el nivel 2 sin tocar la UI ni el dominio de descriptores — solo se añade la fuente de datos HR y el modelo Keytel ya presente.
- **Las fórmulas son knowledge, no trivia.** Cualquier PR que las cambie sin cita a fuente y sin ADR se rechaza. Esto protege el proyecto de "optimizaciones" no fundamentadas.
- **El error residual (±25–35%) se documenta en la UI y en la documentación del usuario.** No se esconde.

---

## Alternativas descartadas

### Heurística "35 kcal/km"
**Descartada.** No es una constante fisiológica: es una aproximación de app genérica (≈3.5 MET × 70 kg × distancia). Ignora que la energía depende de **potencia × tiempo**, no de distancia. Dos rides con la misma potencia pero distinta distancia gastan lo mismo; la heurística los califica mal. Además asume una masa corporal fija implícita.

### ML on-device en v1
**Descartado.** Complejidad no justificada para v1: requiere dataset etiquetado, entrenamiento, modelo embebido, y aún así no superaría el techo de los datos de entrada (sin HR ni potencia, el techo es ±25–35% aunque el modelo sea perfecto). Se puede reconsiderar si en el futuro hay sensores de calidad y datos de calorimetría propios.

### Presentar un único número "sin ruido"
**Descartado.** Irresponsable desde el punto de vista de producto y de honestidad científica. Los wearables comerciales ya cometen este error (Wallen 2016); BuzzBike no lo va a repetir.

### Usar solo Compendium aunque haya HR
**Descartado.** Si el usuario en el futuro conecta un HR strap, la ecuación de Keytel (con datos reales de HR) es objetivamente mejor estimador que las bandas de velocidad. La cascada existe justamente para mejorar cuando hay mejores inputs.

### Inventar una "fórmula propia mejorada"
**Descartada.** Prohibido por regla del proyecto (AGENTS.md §6). Sin calorimetría de referencia propia, no hay forma de validar una fórmula nueva. Se usan las publicadas y auditables.

---

## Fuentes

| Fuente | Referencia | Uso |
|---|---|---|
| Keytel et al. 2005 | https://www.tandfonline.com/doi/full/10.1080/02640410470001730089 | Nivel 2 — ecuación calorías con/sin VO2max |
| Compendium of Physical Activities 2024 | https://pacompendium.com/bicycling/ | Nivel 3 — METs por banda de velocidad |
| ACSM Cycle METs | https://exrx.net/Calculators/CycleMETs | Nivel 1 — VO2 a partir de potencia |
| Mifflin-St Jeor | Estándar clínico de BMR | Componente de reposo |
| Wallen et al. 2016, PLOS One | DOI: `10.1371/journal.pone.0154420` | Reality check de error de wearables (9–43%) |

---

## Actualización 2026-10-27 — integración por segmentos, perfil opcional y corrección de incertidumbre

> **Alcance.** Esta sección **agrega precisiones y corrige** el ADR original; no borra la decisión de la cascada de 3 niveles ni sus reglas de código. Donde haya conflicto entre esta sección y el texto de 2026-10-02, **prevalece esta sección** (ver §7).

### 1. Corrección del ejemplo del Contexto

El Contexto original afirma:

> "A 150 W vs 250 W constante, la distancia cambia pero el gasto calórico es el mismo. Un ride de 20 km en plano a 30 km/h y uno de 40 km a 15 km/h con la misma potencia gastan **lo mismo**."

**Por qué es imprecisa.** La energía mecánica es **potencia × tiempo** (`E = P × t`), no potencia sola ni distancia. Dos rides con la misma potencia pero distinta duración **no** gastan lo mismo: el de mayor duración gasta proporcionalmente más. Y dos rides con distinta potencia pero la misma duración tampoco gastan lo mismo: el de mayor potencia gasta más.

**Formulación correcta:**

- A **igual potencia y distinta duración**, la energía escala con la duración (el doble de tiempo = el doble de energía), sin importar la distancia recorrida.
- A **igual potencia y duración**, la distancia puede cambiar (viento, pendiente, superficie) y la energía mecánica es la misma.
- A **distinta potencia y distinta duración**, hay que comparar `P × t`; comparar solo potencia o solo distancia lleva a conclusiones falsas.

Ejemplo numérico (energía mecánica, sin eficiencia metabólica):

| Ride | Potencia | Duración | Distancia / velocidad | Energía mecánica |
|---|---|---|---|---|
| A | 150 W | 40 min | 20 km a 30 km/h | 150 × 2400 = 360 kJ |
| B | 150 W | 160 min | 40 km a 15 km/h | 150 × 9600 = 1440 kJ |
| C | 250 W | 40 min | depende de la velocidad | 250 × 2400 = 600 kJ |

A y B tienen la misma potencia pero B gasta 4× más energía porque dura 4× más. A y C duran lo mismo pero C gasta más porque la potencia es mayor. La distancia por sí sola no determina el gasto.

> La frase del Contexto se conserva por trazabilidad, pero se considera **corregida por esta subsección**.

### 2. Tabla MET exacta (Compendium 2024)

Valores usados por el motor v1, según **Herrmann et al. 2024** (DOI `10.1016/j.jshs.2023.10.010`). No completar ni interpolar huecos sin fuente.

| Código | Actividad | MET | Velocidad original (mph) | km/h equivalente | Estado |
|---|---|---|---|---|---|
| 01010 | Bicicleta, <10 mph | 4.0 | <10 | <16.09 | Publicado |
| 01020 | Bicicleta, 10–11.9 mph | 6.8 | 10–11.9 | 16.09–19.15 | Publicado |
| 01030 | Bicicleta, 12–13.9 mph | 8.0 | 12–13.9 | 19.31–22.37 | Publicado |
| 01040 | Bicicleta, 14–15.9 mph | 10.0 | 14–15.9 | 22.53–25.59 | Publicado |
| 01050 | Bicicleta, 16–19 mph | 12.0 | 16–19 | 25.75–30.58 | **ESTIMADO** |
| 01060 | Bicicleta, >20 mph | 16.8 | >20 | >32.19 | Publicado |
| 01003 | MTB, uphill, vigorous | 14.0 | n/a | n/a | Medido |

**Aclaraciones obligatorias:**

1. **No es una función continua.** No existe un MET para "cualquier" velocidad; solo hay bandas discretas.
2. **Bandas solapadas y huecos.** `01003` (modalidad/desnivel) se solapa conceptualmente con las bandas de velocidad, y hay huecos numéricos: **19–20 mph (30.58–32.19 km/h)** no cae en `01050` ni en `01060`.
3. **`01050` es ESTIMADO**, no un valor medido publicado. Debe marcarse así en el código y en cualquier documentación derivada.
4. **Los km/h son derivados** con `1.609344`. La unidad canónica de la tabla es **mph**; usar mph para evitar errores de redondeo y convertir solo en la capa de presentación o en un helper testeado.
5. Definir y testear la convención de borde de cada banda (inclusive/exclusive) para que una muestra no caiga en dos bandas ni en ninguna. `TODO:` documentar la convención elegida en el código.

### 3. Integración sample-a-sample

La tabla anterior se integra **muestra a muestra**, no sobre el promedio del ride:

```
kcal_total = Σ_i [ MET(v_i) × 3.5 × kg / 200 × Δt_i(min) ]
```

donde `v_i` es la velocidad suavizada del segmento `i`, `kg` la masa corporal y `Δt_i` la duración del segmento en minutos.

**Reglas de implementación:**

1. **Usar MET total (gross), no neto.** La fórmula anterior ya incluye el metabolismo de reposo implícito en el MET bruto. Restar el reposo además del MET produciría doble descuento. Ver Byrne 2005 sobre la diferencia bruto/neto.
2. **Suavizar la velocidad con una ventana móvil de 10–30 s** antes de clasificar la banda. La ventana exacta es una decisión de ingeniería: definirla como constante, documentarla y calibrarla con datos propios (`TODO:`).
3. **No usar solo la velocidad promedio del ride.** La tabla es escalonada y un promedio puede clasificar todo el ride en una banda que ninguna parte del ride tuvo; además, las paradas contaminan el promedio.
4. **No segmentar por debajo de ~1 min.** Los MET del Compendium son promedios de estado estacionario; segmentos sub-minuto (semáforos, arranques) no son representativos. Fusionar segmentos cortos con el vecino o acumular por estado.
5. **Calcular `Δt_i` desde timestamps, no desde la cantidad de muestras.** La frecuencia de GPS puede variar.
6. Si un segmento no tiene velocidad válida, no asignarle un MET arbitrario: tratarlo según §4 (parada/pausa) o excluirlo y registrar el hueco (`TODO:` definir umbral).

### 4. Paradas

| Contexto de cálculo | Regla v1 | Base |
|---|---|---|
| **Total del ride** (incluye reposo) | Paradas a **1.0 MET** (Compendium `07021`) o **Mifflin REE / 1440** si el perfil está completo | Compendium 2024; Mifflin 1990 |
| **Gasto activo** (solo movimiento) | Excluir las paradas del sumatorio, o usar **net MET** `(MET − 1)` | Byrne 2005 (bruto vs neto) |
| **Prohibido** | Usar Keytel o ACSM durante paradas | Keytel puede dar negativo con HR baja; ACSM asume pedaleo |
| **UI** | Mostrar **"total"** y **"activo"** por separado y etiquetados | Este ADR |

- `Mifflin REE / 1440` convierte kcal/día a kcal/min y sustituye a `1.0 MET` como baseline de reposo cuando hay perfil completo.
- No sumar "total" y "activo" como si fueran magnitudes distintas del mismo cálculo: son dos vistas del mismo ride.
- Un resultado de Keytel ≤ 0 en una parada no se recorta en silencio: se descarta ese valor, se cae al nivel 3 y se registra el fallback (`TODO:` validar el umbral).

### 5. Perfil opcional

El perfil es **opcional**: la app trackea y estima sin él. Más inputs mejoran el modelo, pero su ausencia no bloquea el ride ni la estimación.

#### 5.1 Mifflin-St Jeor (REE, kcal/día)

| Sexo | Ecuación |
|---|---|
| Hombre | `10×kg + 6.25×cm − 5×edad + 5` |
| Mujer | `10×kg + 6.25×cm − 5×edad − 161` |

Fuente: Mifflin et al. 1990, DOI `10.1093/ajcn/51.2.241`. Requiere peso, altura, edad y sexo. Se usa como baseline de reposo (§4), no como modelo de gasto activo.

#### 5.2 Keytel 2005 (kJ/min; kcal/min = kJ/min ÷ 4.184)

| Variante | Sexo | Ecuación (kJ/min) |
|---|---|---|
| Sin VO2max | Hombre | `−55.0969 + 0.6309×HR + 0.1988×kg + 0.2017×edad` |
| Sin VO2max | Mujer | `−20.4022 + 0.4472×HR − 0.1263×kg + 0.074×edad` |
| Con VO2max | Hombre | `−95.7735 + 0.6344×HR + 0.4044×VO2max + 0.3942×kg + 0.2713×edad` |
| Con VO2max | Mujer | `−59.3954 + 0.4498×HR + 0.3802×VO2max + 0.1032×kg + 0.2735×edad` |

Fuente: Keytel et al. 2005, DOI `10.1080/02640410470001730089`. Requiere HR, peso, edad y sexo; VO2max es opcional. Es el nivel 2 de la cascada y solo se activa con HR disponible.

#### 5.3 Regla de masa corporal y datos faltantes

1. **Sin peso no hay kcal absoluta.** Mostrar **MET-min** (`MET × minutos`) o **"no disponible"**. No inventar una masa.
2. Si el producto decide usar **70 kg**, etiquetarlo explícitamente como **"supuesto: 70 kg"**. Es el *reference man* histórico, **no** una imputación estándar ni una estimación del usuario.
3. **Sin edad o sin sexo no se usa Keytel ni Mifflin.** No imputar edad/sexo.
4. Edad, sexo y altura son **opcionales**. El perfil se puede **modificar y borrar**; borrarlo debe volver al cálculo sin perfil, no romper rides históricos (esos conservan el modelo y los inputs con los que se calcularon).
5. El perfil es dato local (AGENTS.md §7): no se envía a Hermes ni a terceros.

### 6. Pendiente / desnivel

- **No inventar una corrección por pendiente.** En las fuentes consultadas no hay una corrección validada para ciclismo sin potenciómetro.
- **`01003` solo aplica a MTB uphill vigoroso**, no a subidas de ruta en general.
- En ruta, documentar que **las subidas se subestiman** y **ampliar el intervalo** de incertidumbre (§7). El factor de ampliación queda `TODO:` hasta tener fuente o calibración propia.
- **No aplicar ecuaciones ACSM de walking/running con grado al ciclismo.** El modelo y la mecánica no son transferibles.
- `TODO:` buscar/validar una corrección de pendiente específica para ciclismo (requiere potencia o un modelo validado).

### 7. Incertidumbre

Esta subsección **reemplaza** los valores `±25–35%` (nivel 3) y `±5–10%` (nivel 1) del texto original, que se presentaban sin fuente. Las bandas siguientes son **bandas de producto**, no intervalos de confianza.

| Modelo / inputs | Banda declarada | Naturaleza | Nota |
|---|---|---|---|
| MET por velocidad, **sin perfil** | **±30–40%** | Banda de producto, **NO** intervalo de confianza | Caso v1 por defecto |
| MET por velocidad, **con perfil** | **±20–30%** | Banda de producto, **NO** intervalo de confianza | Requiere peso/altura/edad/sexo |
| **Keytel con HR** | LoA publicados (tabla siguiente) | Límites de acuerdo de Bland-Altman publicados | No convertir a un `±%` simétrico |
| **ACSM con potencia** | Sin LoA publicado en las fuentes consultadas | No cuantificada | **No** presentar `±5–10%` como intervalo de confianza |
| **Wallen 2016** | 9–43% | Reality check de 4 wearables | **No** es el intervalo de confianza de este modelo |

**Keytel 2005 — sesgo y límites de acuerdo (LoA):**

| Variante | Sesgo (bias) kJ/min | LoA kJ/min | Sesgo kcal/min | LoA kcal/min |
|---|---|---|---|---|
| Sin VO2max | +6.27 | −12.65 a +25.19 | +1.50 | −3.02 a +6.02 |
| Con VO2max | +8.19 | −9.83 a +26.21 | +1.96 | −2.35 a +6.26 |

Conversión: `kcal/min = kJ/min ÷ 4.184`, redondeado a 2 decimales.

**Advertencias:**

- El LoA publicado es una tasa por minuto. `TODO:` confirmar si es aditivo por sesión antes de escalarlo por la duración total; no extrapolarlo linealmente sin verificar.
- Wallen 2016 (DOI `10.1371/journal.pone.0154420`) mide el error de wearables comerciales, **no** el de este modelo. Se usa como reality check, no como banda de error propia.
- Los valores `±25–35%` y `±5–10%` del ADR original quedan **superados** por esta sección.

### 8. UI

1. Mostrar siempre **rango**, **modelo** e **inputs usados y faltantes**. Ejemplo: `"≈600 kcal (rango 390–810; modelo velocidad, sin perfil)"`.
2. Redondear a **decenas** y **sin decimales**: `≈600 kcal`, no `604.3 kcal`.
3. **No llamar "±X%" a un intervalo de confianza.** Decir "rango estimado" o "banda de producto".
4. Si el modelo entrega LoA asimétricos (Keytel), mostrar el rango derivado de esos LoA, no un `±%` simétrico inventado.
5. Sin peso: mostrar `MET-min` o `"no disponible"` y qué dato falta para obtener kcal absolutas. Ejemplo: `"142 MET-min (sin peso; no se muestra kcal absoluta)"`.
6. Mostrar **total** y **activo** por separado (§4), con etiquetas claras.
7. Si se usó un supuesto, mostrarlo: `"supuesto: 70 kg"`, `"modelo velocidad (sin perfil)"`, `"fallback desde Keytel"`.
8. Nunca presentar un número único como exacto (regla original vigente).

### 9. Fuentes

| Fuente | Referencia | Uso en esta actualización |
|---|---|---|
| Herrmann et al. 2024 — Compendium of Physical Activities | DOI `10.1016/j.jshs.2023.10.010` | Tabla MET, códigos `01010`–`01060`, `01003`; `01050` estimado |
| Keytel et al. 2005 | DOI `10.1080/02640410470001730089` | Ecuaciones Keytel y LoA publicados |
| Mifflin et al. 1990 | DOI `10.1093/ajcn/51.2.241` | Mifflin-St Jeor (REE) |
| Wallen et al. 2016 — PLOS One | DOI `10.1371/journal.pone.0154420` | Reality check de wearables (9–43%) |
| Byrne et al. 2005 | DOI `10.1152/japplphysiol.00023.2004` | Distinción MET bruto/neto; supuesto `3.5 ml/kg/min` |
| Bushman 2023 | DOI `10.1249/FIT.0000000000000839` | Soporte metodológico de METs (`TODO:` confirmar título/autores exactos antes de citar en KDoc) |
| Compendium — bicycling | https://pacompendium.com/bicycling/ | Códigos y velocidades de ciclismo |
| Compendium — inactivity | https://pacompendium.com/inactivity/ | Código `07021` (paradas, 1.0 MET) |
| Compendium — unit conversions | https://pacompendium.com/unite-conversions/ | Factor `1.609344` (mph ↔ km/h) |

**TODOs abiertos de esta actualización:**

- `TODO:` confirmar títulos/autores exactos de Byrne 2005 y Bushman 2023 antes de usarlos en KDoc.
- `TODO:` validar el tratamiento temporal (por minuto vs por sesión) de los LoA de Keytel.
- `TODO:` calibrar la ventana de suavizado (10–30 s), la duración mínima de segmento (~1 min) y el umbral de hueco de GPS con datos propios.
- `TODO:` no existe corrección por pendiente validada para ciclismo sin potencia en las fuentes consultadas.
- `TODO:` documentar en código la convención de borde de las bandas MET.
