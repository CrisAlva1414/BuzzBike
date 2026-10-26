# ADR-003: Integración con Hermes Agent vía webhook HMAC V2

- **Estado:** Aceptado
- **Fecha:** 2026-10-02
- **Decisor:** Usuario

---

## Context

BuzzBike puede, de forma **opcional**, enviar un reporte de cada ride a un agente de IA que corre en el NAS del usuario. Restricciones y hallazgos de investigación:

- El destino es **Hermes Agent**, el **framework** de Nous Research (MIT): https://github.com/NousResearch/hermes-agent
  - **CUIDADO DE NOMBRE:** Hermes Agent ≠ el modelo LLM Hermes (Hermes 3 8B / Hermes 4.x, open-weights en HuggingFace). Son cosas distintas. El framework es Python, corre en el NAS, y expone webhooks HTTP nativos.
- El NAS es alcanzable desde el teléfono por **Tailscale** (WireGuard) — no hay puertos abiertos a internet.
- La app es local-first: los tracks GPS son datos sensibles (GDPR). Nada puede salir del dispositivo sin acción explícita del usuario.
- El usuario no tiene HR strap ni potenciómetro; el reporte de ride es esencialmente GPS + descriptores + calorías estimadas + wear.

### Capacidades verificadas de Hermes Agent (webhooks)

| Capacidad | Valor |
|---|---|
| Puerto | `8644` |
| Endpoint | `POST /webhooks/<route>` |
| Firma | Header `X-Webhook-Signature-V2` |
| Algoritmo | HMAC-SHA256 hex de `<timestamp>.<body>` |
| Ventana anti-replay | ±300 s |
| Idempotencia | Por delivery ID (cache 1 h en el receptor) |
| Rate limit | 30 req/min |
| Body limit | 1 MB |
| Modo notificación | `deliver_only: true` (notifica sin gastar tokens del LLM) |

---

## Decision

### Patrón de envío: Outbox + WorkManager (local-first)

1. El ride se persiste en Room. Al marcar "enviar al agente", se inserta una fila en una **tabla Outbox** (`pending_webhooks`) con el payload serializado.
2. Un **WorkManager** único (`uniqueWork` con política `KEEP`) con constraint `NetworkType.CONNECTED` y **backoff exponencial** drena la outbox.
3. Cada entrega es un `POST` firmado a `https://<nas-tailscale>:8644/webhooks/<route>`.
4. El secret HMAC vive en **Android Keystore** (hardware-backed si está disponible). **Nunca** en SharedPreferences plaintext ni en logs.

### Firma HMAC V2

- Firma: `X-Webhook-Signature-V2: HMAC_SHA256(secret, "<timestamp>.<body>")` en hex.
- `<timestamp>`: Unix epoch en segundos. El receptor acepta ±300 s de ventana.
- La app debe reintentar con timestamp fresco si el servidor rechaza por ventana expirada (no reenviar el mismo body con timestamp viejo).

### Idempotencia (doble capa)

- **Capa 1 — receptor:** Hermes cachea delivery IDs por 1 h. Eso NO es suficiente solo con la app.
- **Capa 2 — app:** deduplicar por `ride.id` en la outbox. Un mismo ride nunca debe generar dos filas pendientes con el mismo `ride.id` + `route`. Si el usuario reintenta manualmente, se reutiliza la fila existente o se crea una nueva solo si la anterior falló de forma permanente.

### Data minimization (obligatorio)

Por defecto el payload NO incluye el track GPS crudo de alta frecuencia. Incluye:

- `ride.id` (UUID local), timestamps de inicio/fin
- Métricas agregadas: distancia, moving time, velocidad media/pico, desnivel +/-, calorías estimadas con su **modelo e intervalo de incertidumbre** (consistente con ADR-001)
- **Waypoints decimados** (p. ej. 1 punto cada N metros / N segundos) para visualización aproximada
- Estado de wear de componentes al cierre del ride

NO incluye por defecto: track crudo, datos de sensores crudos, identificadores del dispositivo, o cualquier PII además del que el usuario ya aceptó al configurar el webhook.

El usuario puede, en una opción futura, elegir enviar el track completo — pero eso es un cambio de ADR, no un default.

### Transporte

- **Tailscale (WireGuard)** entre teléfono y NAS. El endpoint es la IP Tailscale del NAS (o MagicDNS hostname), no una IP pública.
- HTTPS si el NAS ya lo expone; si no, el canal ya está cifrado por Tailscale. Documentar en la UI que el tráfico va por la tailnet del usuario.
- La app **no** debe configurar ni rotar Tailscale; eso es infraestructura del usuario.

---

## Consecuencias

- **El cálculo es 100% on-device.** Lo que va al agente es un **reporte**, no lógica de negocio. El agente no recalcula calorías ni wear; solo recibe el resumen. Esto protege el offline-first y la privacidad.
- **Sin red, sin drama.** La outbox acumula entregas pendientes; cuando hay red, se drenan. Un viaje sin cobertura no pierde datos: solo difiere el sync.
- **Un bug de firma o de dedupe no corrompe datos locales.** Room es la fuente de verdad; la outbox es un transporte desacoplado.
- **La ventana ±300 s y el rate limit 30 req/min limitan la ráfaga.** El WorkManager con backoff exponencial ya está alineado; no se debe implementar retry agresivo.
- **El límite de 1 MB por body se cumple sin chunking** si se respeta data minimización (un ride de 2 h en JSON compacto + gzip ≈ 100–250 KB).
- **El secret en Keystore** implica que no se puede exportar el secreto fácilmente (feature, no bug) y que migrar de dispositivo requiere re-configurar el webhook en el NAS.
- **Distinguir Hermes Agent del modelo Hermes** en toda la documentación y UI. Confundirlos lleva a configuraciones erróneas (puerto, endpoint, expectativas del agente).

---

## Alternativas descartadas

### Enviar el track GPS crudo por defecto
**Descartado.** Viola data minimization y el principio local-first. Un track de alta frecuencia expone hábitos de desplazamiento (casa, trabajo, rutas habituales) con mucha más granularidad de lo que el agente necesita para un reporte de ride.

### Polling / WebSocket persistente desde la app
**Descartado.** Requiere la app viva y conectada; no funciona con la batería y con el teléfono en el bolsillo del ciclista. WorkManager + outbox es el patrón correcto para entrega diferida en Android.

### Enviar por SMS / email / Telegram bot
**Descartado.** Requiere cuentas de terceros, dependencias frágiles, y no es el destino decidido (Hermes Agent en el NAS vía Tailscale).

### Almacenar el secret HMAC en SharedPreferences sin cifrar
**Descartado.** Cualquier app con permiso de root o backup inseguro lo extrae. Android Keystore es el mínimo aceptable para un secreto que autoriza escritura en el NAS del usuario.

### Reintentar infinitamente sin backoff
**Descartado.** El rate limit de Hermes (30 req/min) y la buena práctica de Android (backoff exponencial) lo prohíben. Además quemaría batería y podría hacer ban al webhook route.

### Usar el LLM del agente como parte del cálculo de calorías
**Descartado.** El cálculo de dominio físico vive en `domain/` de la app, con fórmulas citadas (ADR-001). Delegar ese cálculo a un LLM lo volvería no auditable y no reproducible. El agente recibe el reporte ya calculado.

### Enviar por endpoint público de internet (VPS)
**Descartado.** El destino decidido es el NAS del usuario vía Tailscale. Exponer un endpoint público agranda el superficie de ataque sin necesidad (no hay multi-usuario).

---

## Fuentes

| Fuente | Referencia |
|---|---|
| Hermes Agent (framework, MIT) | https://github.com/NousResearch/hermes-agent |
| Tailscale | https://tailscale.com/ |
| Android Keystore | https://developer.android.com/privacy-and-security/keystore |
| WorkManager (constraints, backoff, unique work) | https://developer.android.com/topic/libraries/architecture/workmanager |
