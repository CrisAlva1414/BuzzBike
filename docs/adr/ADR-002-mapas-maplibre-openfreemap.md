# ADR-002: Stack de mapas = MapLibre + OpenFreeMap

- **Estado:** Aceptado
- **Fecha:** 2026-10-02
- **Decisor:** Usuario

---

## Context

BuzzBike necesita mostrar el track de la bici sobre un mapa. Restricciones:

- La app es **open source bajo licencia MIT** y se distribuye por GitHub Releases (sin Play Store).
- El usuario pidió mapas "open source" / libres.
- El proyecto está greenfield; no hay deuda técnica previa que defender.

Se evaluaron las opciones disponibles en el ecosistema Android de mapas (2026-10).

---

## Decision

Usar **MapLibre Native Android** (v13.x, licencia BSD 2-Clause, muy activo) como renderer, con **OpenFreeMap** como fuente de tiles vectoriales.

- **OpenFreeMap:** vector tiles, **sin API key**, **sin límites de uso** declarados.
- **Modo online primero.** El modo offline con **PMTiles** queda diferido a una versión futura (ver consecuencias).
- **User-Agent identificable** de la app en las requests de tiles — no un UA genérico.

### Atribución obligatoria (visible en la UI, no escondida)

La app debe mostrar de forma permanente y legible durante el uso del mapa:

1. **"© OpenStreetMap contributors"** + link a https://www.openstreetmap.org/copyright
2. **"© OpenMapTiles"** + link a https://openmaptiles.org/

Esto no es opcional: es requisito de las licencias de los datos y del tile schema. Ocultarlo o minimizarlo demasiado para que sea ilegible es incumplimiento de licencia.

---

## Consecuencias

- **Sin cuenta, sin API key, sin factura.** OpenFreeMap no expone el proyecto a cambios de pricing ni a cuotas que maten la app de golpe.
- **Atribución ODbL visible es parte del UI design.** No es un footnote; es un componente fijo del mapa. Cualquier redesign del mapa debe conservarlo.
- **User-Agent de la app** en requests de tiles: permite a OpenFreeMap identificar el tráfico y evita el bloqueo por UA genérico que sufre tile.openstreetmap.org.
- **Offline diferido (PMTiles).** Se decidió online primero. El soporte PMTiles en MapLibre es nativo desde v11.7.0 vía `pmtiles://file://$path` (hay que copiar el archivo a `filesDir`; `pmtiles://asset://` no funciona). Cuando se agregue offline, deberá ser un ADR nuevo que documente el flujo de descarga, licencia de los tiles offline y actualizaciones.
- **Sin vendor lock-in.** MapLibre es BSD y OpenFreeMap es open; la app no queda atada a un proveedor que pueda cambiar TOS mañana.
- **MIT compatible.** A diferencia de Google Maps Platform / HERE / MapTiler, no hay cláusulas de TOS que restrinjan una app MIT open source.

---

## Alternativas descartadas

### osmdroid
**Descartado.** El proyecto está **archivado desde noviembre 2024**. No recibe mantenimiento; usarlo es deuda técnica activa.

### tile.openstreetmap.org como tile source
**Descartado.** La política de uso del OSMF **prohíbe explícitamente offline/prefetch** y **bloquea apps con User-Agent genérico**. No es una fuente sostenible para una app que además quiere camino a offline PMTiles.

### Google Maps Platform
**Descartado.** Los Terms of Service son incompatibles con una app open source bajo licencia MIT (restricciones de redistribución, branding, y uso del SDK en forks). Además introduce API key, cuotas y cuenta de facturación.

### HERE Maps / MapTiler
**Descartado.** Mismo problema de TOS: licencias propietarias incompatibles con distribución MIT abierta en GitHub Releases.

### MapLibre + otro tile source propietario "gratis"
**Descartado.** Cualquier tile source con ToS propietarias o con cuotas "gratis pero frágiles" reproduce el problema de Google/HERE. OpenFreeMap es la opción que cumple el objetivo de libres + sin API key.

### Renderizar con WebView + leaflet/osm.js
**Descartado.** Rendimiento inferior, integración torpe con Compose, y no aprovecha MapLibre Native que es la decisión ya tomada.

---

## Fuentes

| Fuente | Referencia |
|---|---|
| MapLibre Native Android | https://github.com/maplibre/maplibre-native (v13.x, BSD 2-Clause) |
| OpenFreeMap | https://openfreemap.org/ |
| OpenStreetMap copyright / ODbL | https://www.openstreetmap.org/copyright |
| OpenMapTiles | https://openmaptiles.org/ |
| osmdroid (archivado) | https://github.com/osmdroid/osmdroid |
| Política de uso de tile.openstreetmap.org | https://operations.osmfoundation.org/policies/tiles/ |

---

## Actualización 2026-11-01 — implementación en U5

> **Alcance.** Registra las decisiones tomadas al **implementar** este ADR en el
> slice **U5 (Mapa)**: estilo real de tiles, atribución en la UI, artefacto
> MapLibre, ABIs de release, privacidad del track y comportamiento offline. No
> reescribe las secciones anteriores; donde haya conflicto, prevalece este texto
> por ser el más reciente.
>
> **Estado:** aceptado e implementado. **Fecha de la nota:** 2026-11-01.

### 1. Estilo real: Liberty, sin API key

- Estilo usado: **`https://tiles.openfreemap.org/styles/liberty`**
  (`STYLE_URL` en `ui/history/map/RideMap.kt`). **Sin API key**, sin cuenta y
  sin token, tal cual §Decision.
- La app solo pide estilo y tiles a `tiles.openfreemap.org`: no hay segundo
  proveedor ni fallback a Google/MapTiler.
- El mapa **solo se compone al tocar la pestaña `MAPA`** del detalle del ride
  (selector `DATOS`/`MAPA` en `ui/history/RideDetailScreen.kt`): abrir el
  detalle **no descarga ni un tile**.

### 2. Atribución implementada (visible, con links)

Se cumplió §Atribución obligatoria con **dos capas**, no una sola:

1. **Overlay propio, permanente** (`MapAttribution` en `RideMap.kt`), abajo a
   la izquierda sobre panel translúcido (alfa 0.85), con **links reales**:
   - `© OpenStreetMap contributors` →
     https://www.openstreetmap.org/copyright
   - `© OpenMapTiles` → https://openmaptiles.org/
   - `OpenFreeMap` → https://openfreemap.org/
2. El **botón «(i)» de MapLibre sigue habilitado**:
   `map.uiSettings.isAttributionEnabled = true` — en ningún punto se llama a
   `setAttributionEnabled(false)`.

El overlay lleva `contentDescription` para TalkBack y no depende del color de
los tiles de abajo. Cualquier redesign del mapa debe conservar **ambas** capas
(§Consecuencias).

### 3. Artefacto: `android-sdk-opengl`, no `android-sdk`

| | |
|---|---|
| Artefacto usado | **`org.maplibre.gl:android-sdk-opengl:13.7.1`** |
| Artefacto evitado | `org.maplibre.gl:android-sdk` (misma versión 13.7.1) |
| Motivo | En 13.7.1 **`android-sdk` es solo Vulkan**: exigiría Vulkan en el dispositivo/emulador, incompatible con **minSdk 26** y con el emulador local `tester36`. |
| Renderer | Se **fuerza OpenGL** en el arranque: `MapLibre.getInstance(context, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)`. |

Misma versión y misma API pública que la decisión original; cambia solo el
backend del renderer. **La dependencia no se quitó** — la opción «sacarla si no
entra en v1» quedó descartada (ver `docs/roadmap.md` §0, Fase 4).

### 4. ABIs por variante y tamaño del APK

| Variante | `abiFilters` |
|---|---|
| **release** | `arm64-v8a` + `armeabi-v7a` (los dos ABIs de teléfonos reales) |
| **debug** | **sin filtro**: conserva `x86`/`x86_64` para que el emulador pueda cargar el renderer nativo |

Efecto medido: el **APK de debug pasó de ~84.5 MB a ~70 MB**. El de release
queda todavía más chico al no empaquetar las ABI de emulador.

### 5. Privacidad — trade-off documentado

- **El track nunca sale del dispositivo.** Se construye un `FeatureCollection`
  GeoJSON **en memoria** (`domain/history/TrackGeoJson.kt`) y se inyecta como
  `GeoJsonSource` local. **No se envía ninguna coordenada** a OpenFreeMap ni a
  ningún otro servicio: los requests de tiles no llevan datos del ride.
- **Sí se revela el viewport aproximado.** Para pintar el mapa la app pide
  tiles `z/x/y`, lo que informa al host de tiles (OpenFreeMap) **en qué área y
  con qué zoom se está mirando** — el bounding box aproximado del ride es
  deducible de esas peticiones, más los metadatos HTTP habituales.
- **Ese intercambio ocurre solo por acción explícita del usuario.** No hay mapa
  en background ni precarga: el primer tile se pide cuando la persona toca
  `MAPA`. Es el trade-off de la decisión **online primero** (§Decision): sin
  tiles no hay mapa, y con tiles el host ve el área consultada.
- El resto de `AGENTS.md` §7 no cambia: sin analytics, sin cuentas, sin
  telemetría de ubicación.

### 6. Comportamiento offline y timeout de estilo

- **Sin red validada no se instancia el mapa.** Antes de componer el `MapView`
  se consulta `ConnectivityManager` exigiendo `NET_CAPABILITY_INTERNET` **y**
  `NET_CAPABILITY_VALIDATED` (no basta con Wi-Fi asociado: puede ser portal
  cautivo o modo avión con Wi-Fi). Sin eso el estado es `Failed` y se muestra
  el aviso **«Mapa no disponible sin conexión»** + botón **`REINTENTAR`**;
  MapLibre **nunca** se crea offline (en ese caso encola peticiones y queda en
  blanco para siempre).
- **Timeout de carga de estilo: 9 s** (`STYLE_LOAD_TIMEOUT_MS = 9_000`). Si
  `Style.OnStyleLoaded` no llega en ese plazo (red que se cortó, host colgado),
  el mapa pasa a `Failed` y ofrece el mismo `REINTENTAR`. Es necesario porque
  MapLibre **no emite error** cuando la red es inalcanzable: solo encola.
- **`REINTENTAR`** recrea el `MapView` con un `attempt` nuevo (no reutiliza un
  renderer a medias) y solo procede si vuelve a haber red validada.
- La máquina de estados vive en `ui/history/map/RideMapState.kt`, sin tipos de
  Compose/Android/MapLibre, y está testeada en JVM: `NoTrack` (< 2 puntos
  válidos), `Loading`, `Failed`, retry y timeout.

### 7. Permiso `ACCESS_WIFI_STATE` (merge del AAR)

- **No se declara en el `AndroidManifest.xml` de la app**: lo **aporta el merge
  del AAR** `android-sdk-opengl`, que lo necesita para detectar tiles
  disponibles por Wi-Fi. Es la única fuente de ese permiso en el manifiesto
  fusionado.
- **No se quita** (sin `tools:node="remove"`): eliminarlo rompería la detección
  de conectividad del SDK sin un beneficio de privacidad medible. Queda
  documentado en el manifiesto para que quien reemplace MapLibre revise el
  merged manifest antes de asumirlo.

### 8. Verificación y pendiente

- Gate JVM en **576 tests, 0 fallos**; nuevos: `TrackGeoJsonTest`,
  `RideMapStateTest` y tests del selector de pestaña.
- **Pendiente:** el criterio «User-Agent identificable» de `docs/roadmap.md` §3
  **no se personalizó en U5** — no hay código que fije un UA propio; el UA
  efectivo es el que MapLibre construye por defecto. Verificarlo antes del
  release.
