# research/ — Informes de investigación

Índice de los informes de investigación que respaldan las decisiones de BuzzBike.

La bitácora cruda del pipeline (con el detalle completo de cada researcher) vive en [`state/loop-state.md`](../state/loop-state.md) — ese archivo es bitácora del orchestrator y **no se edita a mano**.

---

## Informes

### 1. Sensores Android + cálculo calórico

**Cubierto en:** `state/loop-state.md` → researcher #1; decisiones en [`docs/adr/ADR-001-modelo-calculo-calorico.md`](../docs/adr/ADR-001-modelo-calculo-calorico.md)

**Resumen:** Foreground Service `health|location` obligatorio en A14+; cap de sensores a 200 Hz en Android 12+ sin `HIGH_SAMPLING_RATE_SENSORS`; FusedLocationProvider con `PRIORITY_HIGH_ACCURACY` basta para v1 (raw GNSS/PPP descartado); barómetro como fuente primaria de desnivel; Activity Recognition como gate de sesión. Calorías: relojes comerciales tienen 9–43% de error vs calorimetría; sin sensores externos el techo es ±25–35%; "35 kcal/km" no es constante fisiológica.

**Fuentes citadas:**

| Fuente | Referencia | Uso en BuzzBike |
|---|---|---|
| Keytel et al. 2005 | https://www.tandfonline.com/doi/full/10.1080/02640410470001730089 | Ecuación calorías con/sin VO2max (nivel 2 de la cascada) |
| Compendium of Physical Activities 2024 | https://pacompendium.com/bicycling/ | METs por banda de velocidad (nivel 3, camino crítico v1) |
| ACSM Cycle METs | https://exrx.net/Calculators/CycleMETs | VO2 a partir de potencia (nivel 1; requiere potenciómetro futuro) |
| Wallen et al. 2016, PLOS One | DOI: `10.1371/journal.pone.0154420` | Reality check: error 9–43% de wearables vs calorimetría |
| Android sensor sampling | Android documentation (A12+ `minDelay` / `HIGH_SAMPLING_RATE_SENSORS`) | Cap de 200 Hz |
| FusedLocationProviderClient | Android Play Services Location | Tracking GPS v1 |

---

### 2. Hermes Agent / integración webhook

**Cubierto en:** `state/loop-state.md` → researcher #2; decisiones en [`docs/adr/ADR-003-hermes-agent-webhook-hmac.md`](../docs/adr/ADR-003-hermes-agent-webhook-hmac.md)

**Resumen:** **Hermes Agent** es el **framework** de Nous Research (MIT, Python, webhooks HTTP nativos en puerto 8644, firma HMAC V2). **No es el modelo LLM Hermes** (Hermes 3 8B / Hermes 4.x, open-weights en HuggingFace) — son cosas distintas y la documentación del proyecto debe distinguirlas siempre. Firma `X-Webhook-Signature-V2` = HMAC-SHA256 de `<timestamp>.<body>`, ventana ±300 s; idempotencia por delivery ID (cache 1 h); rate limit 30 req/min; body limit 1 MB. Patrón recomendado: Outbox en Room + WorkManager. Data minimization: métricas + waypoints decimados, no track crudo.

**Fuentes citadas:**

| Fuente | Referencia | Uso en BuzzBike |
|---|---|---|
| Hermes Agent (framework, MIT) | https://github.com/NousResearch/hermes-agent | Destino del sync opcional; especificación de webhooks/HMAC |
| Tailscale | https://tailscale.com/ | Transporte teléfono ↔ NAS (WireGuard) |
| Android Keystore | Android documentation | Almacenamiento del secret HMAC |
| WorkManager | Android documentation | Outbox + constraints + backoff exponencial |

---

### 3. Mapas, formatos y wear tracking

**Cubierto en:** `state/loop-state.md` → researcher #3; decisiones en [`docs/adr/ADR-002-mapas-maplibre-openfreemap.md`](../docs/adr/ADR-002-mapas-maplibre-openfreemap.md) y [`docs/adr/ADR-004-wear-tracking-maximos-normalizados.md`](../docs/adr/ADR-004-wear-tracking-maximos-normalizados.md)

**Resumen:** MapLibre Native Android (BSD, v13.x) muy activo → usar. osmdroid archivado (nov 2024) → no usar. OpenFreeMap: vector tiles sin API key; atribución ODbL obligatoria y visible. tile.openstreetmap.org prohíbe offline/prefetch y bloquea por User-Agent → no usar como tile source. Google Maps / HERE / MapTiler: ToS incompatibles con app MIT. Offline futuro: PMTiles vía `pmtiles://file://$path` (MapLibre ≥11.7.0; hay que copiar a `filesDir`). Exportación: GPX 1.1 + TrackPointExtension; FIT descartado (licencia Garmin). Wear: sin estándar de intercambio entre apps; prior art `higorslva/biketrackd` (casi idéntico) y `liskin/strava-gear`.

**Fuentes citadas:**

| Fuente | Referencia | Uso en BuzzBike |
|---|---|---|
| MapLibre Native Android | https://github.com/maplibre/maplibre-native | Renderer de mapas (v13.x, BSD 2-Clause) |
| OpenFreeMap | https://openfreemap.org/ | Fuente de vector tiles sin API key |
| OpenStreetMap copyright / ODbL | https://www.openstreetmap.org/copyright | Atribución obligatoria visible |
| OpenMapTiles | https://openmaptiles.org/ | Atribución obligatoria visible |
| osmdroid (archivado nov 2024) | https://github.com/osmdroid/osmdroid | Alternativa descartada |
| Política de uso de tile.openstreetmap.org | https://operations.osmfoundation.org/policies/tiles/ | Por qué no usarlo como tile source |
| GPX 1.1 | https://www.topografix.com/GPX/1/1/ | Formato de exportación nativo |
| TrackPointExtension (Garmin) | Extensión GPX para hr/cad/dist | Campos de sensores en la exportación |
| liskin/strava-gear | https://github.com/liskin/strava-gear | Prior art de gear/wear tracking |
| higorslva/biketrackd | https://github.com/higorslva/biketrackd | Prior art Android casi idéntico — revisar antes de codear el módulo de wear |
| bikenance-app | TODO: verificar URL exacta del repo | Prior art mencionado en investigación |
| PMTiles spec / soporte MapLibre | Especificación PMTiles; soporte nativo MapLibre ≥11.7.0 (`pmtiles://file://`) | Modo offline futuro (diferido) |

---

## Cómo leer esta carpeta

1. Para **por qué** el sistema es como es → los ADRs en `docs/adr/`.
2. Para **qué se investigó y qué fuentes se usaron** → este índice + `state/loop-state.md`.
3. Para **cómo escribir código** que respete esas decisiones → `AGENTS.md` y `docs/format/`.

**Regla:** ninguna fórmula de dominio físico se implementa sin cita a fuente presente en este índice o en un ADR. Si falta la fuente → `TODO:` + discusión, no código.
