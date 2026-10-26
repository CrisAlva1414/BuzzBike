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
