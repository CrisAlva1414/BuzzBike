# Contrato de sync con Hermes Agent

- **Versión del contrato:** 1 (2026-10-28)
- **Ámbito:** envío opcional de un ride de BuzzBike a un Hermes Agent que corre
  en el NAS del usuario (ADR-003).
- **Estado:** vigente para la app a partir del slice S3.

Este documento es la fuente de verdad operativa del protocolo. ADR-003 sigue
siendo la fuente de verdad de la decisión de arquitectura; si hay conflicto,
actualizar ambos.

---

## 1. Modos de transporte

BuzzBike soporta dos modos, seleccionados por `senderType`:

| Modo | Uso | Endpoint |
|---|---|---|
| `WEBHOOK` | Webhook nativo de Hermes Agent | `POST https://<nas-tailscale>:8644/webhooks/<route>` |
| `REST` | Receptor REST propio en el NAS | `POST https://<nas-tailscale>:<puerto>/<ruta>` |

**Ambos modos están autenticados con HMAC.** No existe un modo sin firma ni un
"modo de prueba" sin secreto: si no hay secreto provisionado, el sync no se
ejecuta y el worker termina con error de configuración.

Requisitos comunes:

- El endpoint y el health check deben ser **HTTPS**. Se rechaza `http://`
  (tráfico en claro) y cualquier URL con `user:pass@` (userinfo).
- No se rechazan rangos privados ni CGNAT. Tailscale usa `100.64.0.0/10` y
  MagicDNS (`*.ts.net`); bloquearlos rompería el único despliegue soportado.
- Sin red, los rides quedan en la outbox y se reintentan después. No se pierde
  ningún dato local.

---

## 2. Aprovisionamiento del secreto (opción A)

El secreto **no lo genera BuzzBike**. Lo genera el lado receptor para que ambos
lados puedan acordarlo sin compartir material por un canal inseguro.

**Opción A (soportada):**

1. El usuario (o el instalador del NAS) genera un secreto aleatorio de **32
   bytes o más** en el lado de Hermes/NAS. Ejemplo:
   `openssl rand -hex 32` (64 caracteres hex = 32 bytes).
2. El mismo secreto se configura en el receptor (variable de entorno, archivo
   con permisos restringidos, secret manager del NAS, etc.).
3. El usuario pega el secreto en BuzzBike.
4. La app lo guarda **sellado con AES-GCM usando una clave de Android
   Keystore**. El plaintext no se escribe en disco ni se registra en logs.
5. La app puede borrarlo desde la UI; borrarlo deshabilita el sync autenticado.

Propiedades de esta opción:

- El secreto nunca viaja por la red: solo se usa para calcular firmas.
- Migrar de teléfono requiere volver a pegar el mismo secreto (la clave de
  Keystore no se exporta).
- Si el secreto se filtra, hay que rotarlo en ambos lados y volver a pegarlo en
  la app.

Se rechazan secretos de menos de 32 bytes: HMAC-SHA256 con claves más cortas
debilita la resistencia a falsificación.

---

## 3. Headers exactos

Toda petición autenticada (health check y envío de ride) lleva:

| Header | Valor |
|---|---|
| `X-Webhook-Timestamp` | Epoch Unix en **segundos** (decimal, sin milisegundos) |
| `X-Webhook-Signature-V2` | HMAC-SHA256 en **hex minúsculas** sobre `"<timestamp>.<body>"` |
| `Content-Type` | `application/json` en el envío de ride (el health check no envía body) |

El `rideId` **no viaja en un header**: va dentro del body firmado (§5). El
receptor debe deduplicar por `rideId` del body, no por un header no firmado.

Detalle de la firma:

```text
signing_input = ascii("<timestamp>") + "." + body_bytes
signature     = lowercase_hex(HMAC_SHA256(secret, signing_input))
```

- El separador es un punto `.` literal.
- Se firman los **bytes crudos** del body, exactamente como se envían. No se
  re-serializa el JSON ni se normaliza el orden de campos.
- Para el health check el body es vacío: `signing_input = "<timestamp>."`.
- El timestamp debe ser el mismo que viaja en `X-Webhook-Timestamp`.

---

## 4. Health check dedicado

`healthCheckUrl` es **obligatorio y separado** del endpoint de envío. Está
prohibido usar el endpoint de envío como health check.

Comportamiento de la app:

1. Si no hay secreto provisionado → **mal configurado** (no se hace red).
2. Si `healthCheckUrl` falta, está en blanco o es inválida → **mal
   configurado**.
3. En otro caso, `GET <healthCheckUrl>` con:
   - `X-Webhook-Timestamp: <ts>`
   - `X-Webhook-Signature-V2: HMAC_SHA256(secret, "<ts>.")` (body vacío)
4. **Solo HTTP 200 exacto** significa disponible.
5. `204`, cualquier `3xx`, `4xx`, `5xx`, timeout, DNS, conexión rechazada o
   error TLS → **no disponible**. El ride queda en la outbox y se reintenta.
6. Sin redirects: un `302` no se sigue ni se convierte en `200`.

Regla de interpretación para el worker:

| Resultado | Significado | Acción |
|---|---|---|
| Disponible (`Available`) | Puede intentarse el envío | Drenar outbox |
| No disponible (`Unavailable`) | Falla transitoria | Reintentar con backoff |
| Mal configurado (`Misconfigured`) | Falta secreto o URL inválida | Fallar (no reintentar) |

El receptor debe autenticar el `GET` del health check con la misma regla HMAC
que los envíos, incluyendo la ventana anti-replay. Un health check sin firma
válida debe responder `401`/`403`, nunca `200`.

---

## 5. Payload v1

El body es JSON compacto UTF-8, sin `NaN` ni `Infinity` (los valores no
finitos se serializan como `null`). Campos y orden:

| Campo | Tipo | Descripción |
|---|---|---|
| `version` | int | Versión del payload; hoy `1`. |
| `rideId` | string | UUID local del ride. Clave de deduplicación. |
| `startTimeMillis` | long | Inicio del ride, epoch millis. |
| `endTimeMillis` | long \| null | Fin del ride, epoch millis; `null` si sigue abierto. |
| `distanceM` | double | Distancia total en metros. |
| `movingTimeSeconds` | long | Tiempo en movimiento, segundos. |
| `stoppedTimeSeconds` | long | Tiempo detenido, segundos. |
| `elevationGainM` | double | Desnivel positivo acumulado, metros. |
| `elevationLossM` | double | Desnivel negativo acumulado, metros. |
| `caloriesKcal` | double \| null | Calorías estimadas; `null` si no se pudo calcular. |
| `avgSpeedMps` | double | Velocidad media en m/s. |
| `maxSpeedMps` | double | Velocidad máxima en m/s. |
| `waypoints` | array | Puntos decimados para visualización aproximada. |

Cada waypoint:

| Campo | Tipo | Descripción |
|---|---|---|
| `timestampMillis` | long | Epoch millis reconstruido relativo a `startTimeMillis`. |
| `lat` | double | Latitud. |
| `lon` | double | Longitud. |
| `elevationM` | double \| null | Altitud; `null` si ninguna fuente fue usable. |

Reglas de los waypoints:

- Se toma **como máximo un punto cada 10 segundos**.
- El total se limita a **2000 waypoints**; si se excede, se muestrea
  uniformemente conservando el primero y el último.
- El primer y el último punto válidos siempre se conservan.
- Es una aproximación para dibujar el recorrido, **no** el track GPS crudo.

Ejemplo mínimo:

```json
{
  "version": 1,
  "rideId": "3f7c2b1e-9a4d-4c2f-8b7e-2d1a6f5c9e10",
  "startTimeMillis": 1730000000000,
  "endTimeMillis": 1730003600000,
  "distanceM": 24567.8,
  "movingTimeSeconds": 3300,
  "stoppedTimeSeconds": 300,
  "elevationGainM": 312.5,
  "elevationLossM": 298.1,
  "caloriesKcal": 640.0,
  "avgSpeedMps": 7.44,
  "maxSpeedMps": 12.9,
  "waypoints": [
    {"timestampMillis": 1730000000000, "lat": -33.4489, "lon": -70.6693, "elevationM": 570.0},
    {"timestampMillis": 1730000010000, "lat": -33.4495, "lon": -70.6701, "elevationM": 572.0}
  ]
}
```

---

## 6. Reintentos y deduplicación

- `maxAttempts` limita los intentos por fila de la outbox.
- Backoff **exponencial**: base 30 s, multiplicador 2, tope 1 h
  (`30 s → 60 s → 120 s → ... → 3600 s`).
- Cada falla de envío actualiza `nextAttemptAtMillis` de esa fila; el resto del
  lote continúa.
- El health check no disponible deja las filas intactas: no cuenta como
  intento de entrega, solo pospone el drenaje.
- **Dedupe por `rideId` del body firmado:** un mismo ride no genera dos filas
  pendientes simultáneas. El receptor también idempotencia por el `rideId` que
  viene dentro del body firmado, nunca por un header. Reintentos manuales
  reutilizan la fila existente o crean una nueva solo si la anterior falló de
  forma permanente.
- Errores de configuración (falta de secreto, URL inválida) son terminales:
  no se reintentan hasta que el usuario corrija la configuración.

---

## 7. Ventana anti-replay

Responsabilidad del **receptor**:

- Aceptar timestamps dentro de **±300 segundos** respecto de su reloj.
- Rechazar (p. ej. `401`) timestamps fuera de la ventana, aunque la firma sea
  correcta.
- Recordar delivery IDs / firmas vistas durante al menos la ventana para
  rechazar repeticiones exactas.

La app no reenvía el mismo body con un timestamp viejo: cada intento firma con
un timestamp fresco.

---

## 8. Data minimization y retención

Se envía solo lo necesario para un reporte de ride:

- Métricas agregadas + waypoints decimados (ver §5).
- **No** se envía: track GPS crudo de alta frecuencia, sensores crudos,
  identificadores de dispositivo, cuentas, analytics ni PII adicional.
- El envío es opt-in y off por defecto.
- El payload se guarda en la outbox local (Room cifrado con SQLCipher) solo
  mientras está pendiente de entrega.
- El receptor debe definir y documentar su propia retención. Recomendación:
  conservar el ride solo mientras sea útil y borrarlo a pedido del usuario.
- Borrar la app borra los datos locales. La copia del receptor se gestiona en
  el NAS, fuera del alcance de la app.

---

## 9. Verificación HMAC en el receptor (pseudocódigo)

```python
import hmac, hashlib, time

WINDOW_SECONDS = 300

def verify(request, secret: bytes, body: bytes) -> bool:
    timestamp = request.headers.get("X-Webhook-Timestamp")
    signature = request.headers.get("X-Webhook-Signature-V2")
    if not timestamp or not signature:
        return False

    try:
        ts = int(timestamp)
    except ValueError:
        return False

    # Ventana anti-replay del receptor.
    if abs(time.time() - ts) > WINDOW_SECONDS:
        return False

    signing_input = timestamp.encode("ascii") + b"." + body
    expected = hmac.new(secret, signing_input, hashlib.sha256).hexdigest()

    # Comparación en tiempo constante.
    return hmac.compare_digest(expected, signature.lower())

@app.get("/health")
def health(request):
    body = b""
    if not verify(request, SECRET, body):
        return Response(status=401)
    return Response(status=200)  # solo 200 cuenta para la app

@app.post("/webhooks/ride")
def ride(request):
    body = request.body  # bytes crudos, sin re-serializar
    if not verify(request, SECRET, body):
        return Response(status=401)
    payload = json.loads(body)
    save_once(payload["rideId"], payload)  # idempotencia por rideId del body firmado
    return Response(status=204)
```

Notas:

- `verify` debe usar los bytes crudos del body. Re-serializar el JSON cambia
  los bytes y rompe la firma.
- `hmac.compare_digest` evita filtraciones por timing.
- El health check solo responde `200` si la firma es válida; cualquier otra
  cosa es `4xx` y la app lo interpreta como no disponible.
