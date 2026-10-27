# ADR-006: Elevación y desnivel (barómetro + DEM + GPS)

- Estado: Propuesto
- Fecha: 2026-10-27
- Relacionados: ADR-001 (modelo calórico), ADR-002 (mapas)
- Alcance: `domain/` (fusión y descriptores), `sensor/` (GPS/barómetro), `data/` (muestras crudas), `ui/` (fuente e incertidumbre)

---

## Contexto

### El problema

El desnivel acumulado de un ride no es la suma de diferencias de altitud punto a punto. El error vertical de cada muestra se integra y produce **ganancia falsa**: un ride plano puede acumular cientos de metros si se suman rebotes de GPS o de presión. La decisión central de este ADR es separar dos problemas:

1. **Lo relativo** (cuánto subió/bajó el ciclista): lo mide mejor el barómetro, si existe.
2. **Lo absoluto** (a qué altura está respecto del nivel del mar): necesita un ancla externa (GPS MSL o DEM), porque ni el barómetro ni el GPS vertical alcanzan por sí solos.

### Evidencia y restricciones

- **GPS vertical.** `Location.getAltitude()` devuelve altura sobre el elipsoide WGS84, no altura sobre el nivel del mar. `getVerticalAccuracyMeters()` existe desde API 26 pero reporta incertidumbre al **68%** (1σ), no al 95%; no es directamente comparable con las cifras de la especificación GPS. `getMslAltitudeMeters()` y `getMslAltitudeAccuracyMeters()` existen desde **API 34**; con `minSdk 26` hace falta un fallback. Además, `hasMslAltitude()` puede ser `false` según dispositivo/proveedor.
- **Precisión GPS vertical.** La especificación GPS SPS 2020 reporta ≤13 m al 95% global y ≤33 m al 95% en el peor sitio (GPS.gov). Es un orden de magnitud insuficiente para ganancia fina de un ride.
- **Barómetro.** `Sensor.TYPE_PRESSURE` es hardware opcional: hay que chequear `SensorManager.getDefaultSensor(TYPE_PRESSURE) != null`. Su precisión **relativa** es excelente (cm–dm; los sensores citados por HiMeter 2018 declaran ≈1 m relativo, hasta ~1,7 m según sensor), pero su precisión **absoluta** es mala (±10–20 m según sensor) y deriva con el clima. HiMeter midió hasta ~1,2 hPa de variación en 30 min en un punto fijo, equivalente a ~10 m de altitud. La lectura también depende de temperatura/humedad y del modelo de teléfono.
- **DEM.** Copernicus GLO-30 es un **DSM** (modelo de superficie), no un DTM: incluye edificios, infraestructura y vegetación. Su exactitud absoluta es <4 m (90% error lineal) y su exactitud relativa <2 m para pendientes ≤20%, con datum vertical EGM2008. La malla de 30 m no permite ganancia fina ni resuelve el terreno bajo el ciclista en zona urbana o boscosa.
- **Mapas.** OpenFreeMap (ADR-002) entrega vector tiles del esquema OpenMapTiles; no aporta un DEM ni curvas de nivel como fuente de altitud. MapLibre Native Android soporta `raster-dem`/`hillshade` en 2D, pero **no** tiene terreno 3D integrado: el issue [#252](https://github.com/maplibre/maplibre-native/issues/252) sigue abierto y el PR [#4190](https://github.com/maplibre/maplibre-native/pull/4190) está en **Draft** al 2026-10-27 (hay un fork de iOS que funciona, pero no está mergeado).
- **Restricciones del proyecto.** `minSdk 26`, local-first, licencia MIT, sin dependencias nuevas sin revisión, y atribución de datos/tiles obligatoria cuando corresponda (ADR-002). El algoritmo de desnivel es **dominio físico**: toda constante o fórmula debe citar fuente (AGENTS.md §6) y todo cambio requiere ADR.

### Definiciones usadas en este ADR

| Término | Definición |
|---|---|
| `h_ell` | Altura elipsoidal: metros sobre el elipsoide WGS84. Es lo que devuelve `getAltitude()`. |
| `H` | Altura ortométrica / MSL: metros sobre el nivel medio del mar (geoide). Es lo que se muestra como "msnm". |
| `Δh` | Cambio relativo de altura entre dos instantes. Es la magnitud útil para ganancia, pérdida, grade y VAM. |
| Ganancia / pérdida | Suma de `Δh` positivos / negativos aceptados después de la histéresis. No es la diferencia entre altura máxima y mínima. |
| Grade | `Δh / Δs_horizontal × 100`, calculado sobre una ventana, no punto a punto. |
| VAM | Vertical ascent rate: `Δh / Δt` en una ventana corta, usualmente m/h. |

---

## Decisión

1. **Barómetro = fuente relativa principal si existe.**
   Si `TYPE_PRESSURE` está disponible, la señal de presión es la fuente primaria de `Δh`. Si no existe, se usa GPS-only (punto 8). El barómetro no se usa como fuente absoluta sin ancla.

2. **Ancla absoluta al inicio: MSL GPS promediado 20–30 s o DEM en la coordenada de inicio.**
   Al comenzar el ride se establece un par de referencia `(H0, p0)`:
   - Preferir MSL GPS (`hasMslAltitude() == true`) promediado durante 20–30 s, con `hasVerticalAccuracy() == true` y `verticalAccuracy < 10 m`.
   - Si no hay MSL GPS con esa calidad, usar el DEM en la coordenada de inicio como ancla aproximada, marcándola como de menor confianza (el DEM es DSM y puede incluir edificios/vegetación).
   - Re-anclar cada 10 min **solo** si hay un fix con `hasVerticalAccuracy() == true` y `verticalAccuracy < 10 m`. El re-ancla se aplica como un **offset suavizado**, no como un salto abrupto; si no hay fix válido, se conserva el ancla anterior y se amplía la incertidumbre.
   - Si solo hay altitud elipsoidal (`getAltitude()`), no se etiqueta como "msnm": o se convierte con un modelo de geoide (fuera del alcance de v1; ver TODOs) o se muestra únicamente el desnivel relativo.
   - Antes de API 34 no hay MSL directo: el fallback es DEM como ancla o GPS elipsoidal con corrección de geoide explícita. No se usa una constante de conversión.

3. **Suavizado: mediana 3 + Savitzky–Golay (ventana 9 s, orden 2) a 1 Hz.**
   Se remuestrea a 1 Hz, se aplica una mediana de 3 muestras para eliminar picos y luego Savitzky–Golay con ventana de 9 s (9 muestras a 1 Hz) y orden 2. **No se suaviza a través de gaps**: cada segmento continuo se filtra por separado; no se interpola ni se rellena un gap para que el filtro "vea" una serie completa.

4. **De-trending de deriva.**
   En paradas de más de 30 s, si la presión cambia mientras la velocidad horizontal es ~0, ese cambio es deriva ambiental, no desnivel. Se estima la deriva (offset y/o tendencia) con la ventana de la parada y se resta de la señal posterior. Si la deriva es un escalón o no es lineal, se aplica corrección de offset y, si hay un ancla válida, se re-ancla; si no, se amplía la incertidumbre. El umbral exacto de "velocidad ~0" se calibra y se documenta (TODO).

5. **Histéresis de ganancia/pérdida: 3 m con barómetro; 10 m solo GPS.**
   Solo se acumula un cambio cuando el movimiento neto desde el último extremo aceptado supera el umbral; los rebotes menores no cuentan. Ganancia y pérdida se acumulan por separado y el umbral se aplica a ambas. La referencia de industria citada en el brief (Strava) usa >2 m con barómetro y >10 m sin barómetro; **3 m se elige como umbral más conservador para un teléfono**, porque el barómetro del teléfono no está calibrado como un altímetro dedicado.

6. **Grade sobre ventanas de 50–100 m; VAM sobre 5–10 s; no reportar grade en gaps.**
   El grade se calcula con `Δh` suavizado y distancia horizontal acumulada sobre una ventana de 50–100 m (la ventana concreta se elige según velocidad y tasa de muestreo, y se prueba en ambos extremos). La VAM se calcula sobre una ventana móvil de 5–10 s con `Δh` suavizado. Si la ventana cruza un gap de GPS/barómetro, o si la distancia horizontal es demasiado pequeña para una pendiente estable, no se reporta grade. No se calcula grade a partir del DEM.

7. **Guardia de ascensor.**
   Si `|Δh| > 3 m`, el desplazamiento horizontal es `< 5 m` y el acelerómetro indica que el teléfono está quieto/sin vibración de pedaleo, el segmento se descarta de la ganancia/pérdida del ride y se marca como movimiento vertical no ciclista (ascensor, escalera mecánica, etc.). El umbral del acelerómetro se calibra; se documenta el riesgo de falso positivo en rampas muy cortas y lentas y se conservan las muestras crudas para auditoría.

8. **Sin barómetro: GPS-only con histéresis 10 m, marcado en UI.**
   Sin `TYPE_PRESSURE`, se usa la altitud GPS disponible (MSL si existe; si no, elipsoidal con la salvedad del punto 2) y se aplica histéresis de 10 m. La UI debe indicar "GPS" y una incertidumbre mayor; no se promete ganancia fina ni se compara con un ride medido con barómetro como si fueran equivalentes.

9. **DEM solo como ancla/fallback y sanity check; nunca para ganancia fina.**
   El DEM sirve para:
   - ancla inicial cuando no hay MSL GPS;
   - fallback de altitud absoluta;
   - sanity check de tendencia a escala de segmento (comparar la tendencia del desnivel con el DEM, no punto a punto);
   - hillshade 2D.
   No se usa para calcular ganancia/pérdida fina, grade ni VAM. Si el sanity check discrepa, se marca baja confianza; no se corrige silenciosamente la señal del barómetro/GPS con el DEM.

10. **Hillshade 2D opcional; 3D terrain diferido.**
    Se puede ofrecer hillshade 2D con Mapterhorn (`https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`; terrarium 512 según el proveedor, codificación a verificar) y fallback a AWS terrarium PNG (`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`). Es una capa **visual**, no una fuente de medición. El terreno 3D queda diferido: MapLibre Native Android no lo soporta de forma integrada al 2026-10-27 (issue #252 abierto, PR #4190 en Draft); se reevalúa cuando haya release estable. Antes de producción se verifican licencia, atribución y ToS de ambos tile servers.

### Conversión presión → altitud

Para implementar los puntos 1–2 se usa la fórmula barométrica de la atmósfera estándar (US Standard Atmosphere 1976):

`h = 44330 × (1 − (p / p0)^(1 / 5.255))`

donde `p` es la presión medida y `p0` es la presión de referencia. **`p0` no se asume 1013,25 hPa**: con un ancla `(H0, p0_ancla)` se estima la presión de referencia a nivel del mar o se trabaja directamente en diferencias. La fórmula asume una atmósfera estándar; las desviaciones reales de temperatura y humedad son una fuente de error que se refleja en la incertidumbre, no se corrige con una constante inventada.

### Orden de procesamiento propuesto

1. Ingesta de muestras con timestamp monotónico: presión, lat/lon, altitud GPS + accuracy, velocidad, magnitud/varianza del acelerómetro.
2. Segmentación por gaps (sensor o GPS). No interpolación a través de un gap.
3. Conversión presión → `Δh` con el ancla vigente.
4. Mediana 3 + Savitzky–Golay 9 s/orden 2, por segmento.
5. Detección de paradas y de-trending de deriva.
6. Guardia de ascensor.
7. Histéresis de ganancia/pérdida.
8. Grade y VAM.
9. Salida con ganancia, pérdida, grade, VAM, fuente usada y nivel de confianza/incertidumbre.

---

## Tabla de fuentes de altitud

| Fuente | Qué aporta | Error/límite | Uso | No usar para |
|---|---|---|---|---|
| Barómetro del teléfono (`TYPE_PRESSURE`) | `Δh` relativo de alta resolución y bajo consumo; detección de paradas y de movimiento vertical | Absoluto ±10–20 m según sensor; deriva por clima (~1,2 hPa/30 min ≈10 m en HiMeter); no todos los teléfonos lo tienen; depende de temperatura/humedad | Ganancia/pérdida, grade, VAM, guardia de ascensor; relativo entre anclas | Altitud absoluta sin ancla; "msnm" directo; calibración absoluta sin referencia |
| GPS MSL (`getMslAltitudeMeters()`, API 34+) | Altitud ortométrica/MSL y accuracy, si el proveedor la soporta | Precisión vertical GPS ≤13 m 95% global y ≤33 m 95% peor sitio (SPS 2020); accuracy de Android al 68%, no 95%; puede no estar disponible (`hasMslAltitude() == false`); multipath/urban canyon | Ancla inicial, re-ancla, sanity check, fallback sin barómetro | Ganancia fina; grade en ventanas cortas; asumir disponibilidad o geoide concreto |
| GPS elipsoidal (`getAltitude()`, API 1+) | Altura sobre el elipsoide WGS84 | Error vertical del mismo orden que el GPS + separación del geoide (puede ser decenas de m); ruido; sin corrección no es MSL | Último fallback si no hay MSL ni DEM; comparación relativa con cautela | Mostrar "msnm"; ancla sin corrección de geoide; ganancia fina |
| DEM Copernicus GLO-30 | Elevación de superficie (DSM) global de 30 m; ancla y tendencia | <4 m 90% absoluto; <2 m relativo en pendientes ≤20%; incluye edificios/vegetación; malla de 30 m; datum vertical EGM2008 | Ancla inicial/fallback, sanity check de tendencia, hillshade | Ganancia fina; debajo de dosel/edificios; altitud del ciclista en movimiento; grade corto |
| DEM USGS 3DEP (EE. UU.) | DEM de alta resolución (lidar y productos derivados de resolución variable) | Solo EE. UU.; cobertura y resolución variables; puede ser DTM o DSM según producto | Fallback/ancla en EE. UU.; sanity check local | Fuera de EE. UU.; ganancia fina global |
| Hillshade / tiles DEM (Mapterhorn, AWS terrarium) | Visualización 2D del relieve | Hereda el DEM; disponibilidad, licencia y ToS a verificar; no es una medición | Hillshade 2D opcional en el mapa | Cálculo de desnivel, grade o altitud |

**Nota:** ninguna fila es una fuente "exacta". La estrategia es fusión: barómetro para lo relativo, GPS/DEM para el ancla y el sanity check, y UI que declara la fuente y la incertidumbre.

---

## Validación

### Casos sintéticos

| Caso | Qué prueba | Señal de falla |
|---|---|---|
| Rampa conocida (grade constante, sin ruido) | Sesgo de ganancia/pérdida y de grade | Error sistemático mayor que la tolerancia definida |
| Ruido gaussiano (σ creciente sobre plano) | Falsos positivos de ganancia y robustez de la histéresis | Ganancia > 0 en un ride plano |
| Deriva barométrica (lineal y escalón) | De-trending | Ganancia falsa después de una parada |
| Parada >30 s con cambio de presión | No contar el clima como desnivel | Ganancia durante la parada |
| Ascensor (`abs(Δh) > 3 m`, horizontal < 5 m) | Guardia de ascensor | Ganancia aceptada en el ride |
| Gap de GPS o de barómetro | Segmentación y no interpolación | Grade o ganancia inventados en el gap |
| Ascenso corto (20–50 m) | Sensibilidad vs histéresis | No se detecta o se parte en escalones |

### Métricas

- **MAE y RMSE de ganancia y pérdida** contra una referencia independiente, por ride y por segmento.
- **Bland–Altman** de ganancia/pérdida por ride: sesgo medio y límites de acuerdo (no solo correlación).
- **Falsa ganancia en plano/parado**: metros acumulados en un ride sin desnivel real.
- **Error de grade** en ventanas de 50–100 m y **error de VAM** en ventanas de 5–10 s.
- **Tasa de detección/rechazo** de ascensor y de gaps.

La referencia de validación no puede ser el mismo DEM que se usa como ancla: para ganancia fina hace falta una referencia independiente (por ejemplo, una rampa calibrada o levantamiento topográfico/RTK). Los umbrales numéricos de aceptación se fijan antes de implementar y se documentan; este ADR no los inventa.

---

## Consecuencias

- **La UI debe indicar la fuente y la incertidumbre.** Ejemplos de etiqueta: "barómetro", "GPS", "DEM (ancla)" y un intervalo de incertidumbre. No se muestra un número de desnivel como exacto.
- **No prometer altitud absoluta "msnm" sin una cadena MSL verificable.** `getAltitude()` es elipsoidal y requiere corrección de geoide para convertirse en msnm; `getMslAltitudeMeters()` ya es MSL pero no está disponible en todas las versiones/dispositivos y Android no documenta el modelo de geoide usado. Copernicus GLO-30 usa EGM2008 y es un DSM. Si no hay una cadena verificable, se muestra solo desnivel relativo.
- **El algoritmo de desnivel es dominio físico.** Vive en `domain/`, es testeable en JVM puro, cita sus fuentes en KDoc y cualquier cambio de constante/ventana/umbral requiere ADR y fuente (AGENTS.md §6).
- **Persistencia de muestras crudas.** Se guardan presión, altitud GPS + accuracy, timestamps y flags de fuente para poder reprocesar y auditar un ride. Los datos siguen siendo locales; no cambia el modelo de privacidad de AGENTS.md §7.
- **Complejidad acotada.** La fusión agrega filtrado, segmentación, de-trending y tests, pero no requiere una dependencia nueva ni un backend. El barómetro es de bajo consumo; el procesamiento a 1 Hz es trivial.
- **3D terrain no bloquea v1.** El hillshade 2D es opcional; el terreno 3D se reevalúa cuando MapLibre Native lo integre. No se promete una feature que el renderer actual no tiene.
- **Licencias y atribución.** Si se usa Copernicus GLO-30, se debe incluir el aviso de atribución de Copernicus/DLR/Airbus que corresponda. Si se usa Mapterhorn o AWS terrarium, se verifican licencia, atribución y ToS antes de distribuir. Esto es coherente con ADR-002.
- **Riesgo de falsa confianza.** La incertidumbre se deriva de la validación y del estado de la señal (barómetro presente/ausente, calidad del ancla, gaps). No se muestra precisión de centímetros en un teléfono.

---

## Alternativas descartadas

### Solo GPS para todo
**Descartada.** El error vertical de GPS (≤13 m 95% global, ≤33 m 95% peor sitio) y su ruido producen ganancia falsa. La histéresis de 10 m reduce el problema, pero no reemplaza la resolución relativa del barómetro.

### Solo barómetro, absoluto
**Descartada.** La precisión absoluta (±10–20 m) y la deriva climática (~10 m en 30 min en el estudio HiMeter) hacen imposible mostrar "msnm" sin ancla. El barómetro sirve para lo relativo, no para lo absoluto.

### Ganancia calculada con DEM
**Descartada.** Copernicus GLO-30 es un DSM de 30 m: incluye edificios y vegetación, y su malla no resuelve la ganancia fina de un ride. Sirve como ancla/tendencia, no como señal de desnivel.

### Sumar diferencias punto a punto sin histéresis
**Descartada.** Es la causa clásica de ganancia inflada. La histéresis con umbral (3 m con barómetro, 10 m sin barómetro) es obligatoria.

### Usar `getAltitude()` como si fuera MSL
**Descartada.** Es altura elipsoidal. La diferencia con el geoide puede ser de decenas de metros y no es una constante global. Mostrarla como "msnm" sería incorrecto.

### Terreno 3D en v1
**Descartado.** MapLibre Native Android no tiene terreno 3D integrado al 2026-10-27 (issue #252 abierto, PR #4190 Draft). El hillshade 2D cubre la necesidad visual sin bloquear el ride.

---

## TODOs y preguntas abiertas

- [ ] Calibrar el umbral de "velocidad ~0" para paradas y la varianza del acelerómetro para la guardia de ascensor con rides reales.
- [ ] Definir el umbral de gap (sensor/GPS) y la política exacta de segmentación.
- [ ] Definir el blending del re-ancla cada 10 min: límite de salto, suavizado del offset y qué hacer si el fix es bueno pero el salto es grande.
- [ ] Verificar en dispositivos objetivo la disponibilidad real de `hasMslAltitude()`/`getMslAltitudeMeters()` y qué modelo de geoide usa Android (no documentado en la API; no asumir EGM2008).
- [ ] Decidir si se incluye un modelo de geoide para convertir altitud elipsoidal (p. ej. EGM2008) y cómo se implementa/licencia.
- [ ] Verificar la exactitud absoluta por modelo de barómetro; la tabla ±10–20 m es de los sensores citados por HiMeter, no universal.
- [ ] Verificar licencia, atribución y ToS de Mapterhorn y AWS terrarium; confirmar soporte de WebP en `raster-dem` para la versión de MapLibre Native usada.
- [ ] Revisar el estado de MapLibre Native 3D terrain (issue #252 / PR #4190) antes de prometerlo.
- [ ] Fijar criterios numéricos de aceptación (MAE/RMSE/Bland–Altman) con el usuario/orchestrator.
- [ ] Verificar la URL vigente de GPS.gov SPS 2020 (la URL histórica devuelve 404 al 2026-10-27) y la URL exacta de la FAQ de Strava; las cifras usadas son las del brief.
- [ ] Definir el sanity check con DEM: escala, umbral y acción (marcar baja confianza, no corregir).
- [ ] Confirmar que se guardan las muestras crudas necesarias y revisar el impacto en almacenamiento/privacidad.

---

## Fuentes

| Fuente | Referencia | Uso en este ADR | Verificación (2026-10-27) |
|---|---|---|---|
| GPS.gov — 2020 SPS Performance Standard | Documento oficial; URL histórica `https://www.gps.gov/technical/ps/2020-SPS-performance-standard.pdf` | Precisión vertical GPS: 13 m 95% global, 33 m 95% peor sitio | URL 404; cifras provistas por el brief. **Verificar URL vigente (TODO).** |
| Android `Location` reference | https://developer.android.com/reference/android/location/Location | Contrato de `getAltitude()` (WGS84), `getVerticalAccuracyMeters()` (API 26, 68%), `getMslAltitudeMeters()`/`hasMslAltitude()` (API 34) | Verificado |
| AOSP `Location.java` | https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/location/java/android/location/Location.java | Implementación de referencia de la API | URL 200; no se revisó línea por línea |
| US Standard Atmosphere 1976 (NASA TM-X-74335) | https://ntrs.nasa.gov/citations/19770009539 | Fórmula presión–altitud y supuestos de atmósfera estándar | Verificado |
| Bosch Sensortec — white paper de sensores de presión | Título/URL exactos a confirmar | Características de sensores barométricos | **No verificado — TODO** |
| TDK — documentación de sensores de presión | Título/URL exactos a confirmar | Características de sensores barométricos | **No verificado — TODO** |
| HiMeter: Telling You the Height Rather than the Altitude (2018) | https://pmc.ncbi.nlm.nih.gov/articles/PMC6022159/ — PMCID PMC6022159 | Precisión absoluta ±10–20 m, relativa ~1–1,7 m, deriva climática ~1,2 hPa/30 min | Verificado (texto y tabla de sensores) |
| Muralidharan et al. 2014 — "Barometric phone sensors" | DOI `10.1145/2565585.2565596` | Limitaciones de los barómetros de teléfono | DOI resuelto a la referencia; no se descargó el texto completo |
| Heo 2025 | DOI `10.1109/JSEN.2025.3568843` | Referencia de estado del arte provista por el brief; no se usa para constantes de este ADR | **No verificado — TODO** (título/alcance a confirmar) |
| Savitzky & Golay 1964 | DOI `10.1021/ac60214a047` | Suavizado Savitzky–Golay (ventana 9 s, orden 2) | DOI provisto; no se descargó el texto |
| Copernicus DEM — GLO-30 | https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM | DSM, 30 m, <4 m 90% absoluto, <2 m relativo, datum EGM2008, atribución | Verificado |
| USGS 3DEP | https://www.usgs.gov/3d-elevation-program | DEM de alta resolución en EE. UU. como fallback/ancla | URL canónica; 403 desde este entorno, no se pudo abrir |
| Strava — Elevation FAQ | `https://support.strava.com/hc/en-us` (URL exacta a confirmar) | Referencia de industria: >2 m con barómetro, >10 m sin barómetro | **No verificado — TODO**; cifras provistas por el brief |
| MapLibre Native — Terrain3D issue #252 | https://github.com/maplibre/maplibre-native/issues/252 | Estado del soporte de terreno 3D | Verificado: abierto |
| MapLibre Native — Terrain 3D PR #4190 | https://github.com/maplibre/maplibre-native/pull/4190 | Estado de la implementación de terreno 3D | Verificado: Draft al 2026-10-27 |
| MapLibre Style Spec — hillshade | https://maplibre.org/maplibre-style-spec/layers/#hillshade | Soporte de `raster-dem`/`hillshade` 2D | Verificado |
| Mapterhorn | https://tiles.mapterhorn.com/{z}/{x}/{y}.webp | Hillshade 2D opcional (terrarium 512) | Tile 0/0/0 responde 200 WebP; codificación terrarium 512, licencia y ToS **no verificadas** |
| AWS Terrarium | https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png | Fallback de hillshade 2D | Tile 0/0/0 responde 200 PNG; ToS/atribución **a verificar** |
