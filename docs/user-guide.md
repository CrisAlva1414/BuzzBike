# Guía de uso — BuzzBike

**Última actualización: 2026-11-01.** Guía breve para usar la app. Si algo
no coincide con lo que ves en pantalla, abrí un issue: el código manda.

---

## Iniciar un ride

1. Abrí la app: caés en la pantalla de ride (estado **listo/reposo**).
2. Si es la primera vez, la app te pide el **permiso de ubicación**. Tocá
   **DAR PERMISO** y aceptá. Si lo denegaste antes, aparece un mensaje y el
   botón **ABRIR AJUSTES** te lleva a los ajustes del sistema para
   destrabar el permiso; al volver, la sesión se re-evalúa sola.
3. Opcional: elegí la **bici del ride** (selector **BICI DEL RIDE**). Si no
   tenés bicis, agregá una desde **GARAJE** (o andá sin bici: el ride se
   guarda igual, solo que no acumula desgaste).
4. Tocá **INICIAR RIDE**. La app empieza a trackear GPS con un servicio en
   primer plano (notificación persistente de trackeo activo).

---

## Pausar y terminar

- **PAUSA:** congela la captura. La auto-pausa también se dispara sola si
  no detecta movimiento (ADR-005). Podés **SEGUIR** desde donde estabas.
- **TERMINAR:** cierra el ride, calcula descriptores (distancia, moving
  time, velocidades, desnivel) y la banda calórica, y te muestra un
  resumen.
- Si cerrás la app con un ride vivo, al reabrirla te ofrece **salvarlo o
  descartarlo** sin perder lo capturado.

La barra de acción (INICIAR/PAUSA/TERMINAR) queda **fija arriba**, visible
sin scrollear aunque la pantalla sea angosta (320 dp).

---

## Historial y detalle

- **HISTORIAL** (botón desde la pantalla de ride): lista de rides
  ordenados con una **franja semanal** (distancia y cantidad de rides por
  semana calendario).
- Tocá un ride para abrir su detalle, con dos pestañas:
  - **DATOS:** traza + 8 métricas (distancia, moving time, velocidades,
    desnivel, calorías…). La banda calórica se muestra **solo** si el
    modelo y los extremos están completos — nunca se rellena con valores
    inventados. También aparece el nombre de la bici (o «Bici eliminada»
    si ya no existe) y el perfil con el que se calculó ese ride.
  - **MAPA:** el recorrido dibujado con **MapLibre + OpenFreeMap** (sin
    API key). Las coordenadas del track **nunca se envían a ningún
    servicio**: el track se renderiza en local y solo se piden tiles del
    área visible. **Necesita conexión** para cargar el mapa base; sin
    conexión verás un aviso con botón **REINTENTAR**. La atribución
    (`© OpenStreetMap contributors`, `© OpenMapTiles`, `© OpenFreeMap`) es
    permanente y con links.

---

## Exportar GPX

En el detalle del ride, **EXPORTAR GPX** genera un archivo **GPX 1.1 +
`TrackPointExtension`** (incluye cadencia, potencia y corazón si algún día
se capturan; hoy los campos que haya). El archivo se guarda donde **vos
elijas** vía el selector de archivos del sistema (Storage Access Framework,
destinos `content://`): una carpeta de tu elección, un servicio en la nube
si usás uno, etc. BuzzBike no vuelve a leer ese archivo.

---

## Perfil corporal (opcional)

En **Ajustes** podés cargar **peso, altura, edad y sexo**.

- **Para qué sirve:** estrecha la banda de incertidumbre calórica de
  **±30–40%** a **±20–30%** y activa Mifflin-St Jeor como baseline de
  paradas (en vez de un MET genérico). Sin perfil, la app no inventa nada:
  usa la banda ancha y etiqueta el peso de referencia como
  «supuesto: 70 kg» cuando aplica.
- **Es opcional.** Podés no cargarlo y usar la app completa; solo perdés
  precisión en las calorías.
- **Privacidad:** se guarda **solo en este dispositivo**, en la misma base
  cifrada que los rides (Room + SQLCipher, clave en Keystore). **Nunca** se
  envía al sync ni a terceros. Cada ride guarda un **snapshot** de los
  campos con los que se calculó: podés borrar o editar el perfil sin tocar
  el historial.
- **Borrar el perfil** (confirmación en la UI) borra solo eso; las rutas y
  calorías históricas quedan igual.

---

## Garaje y alertas de desgaste

En **GARAJE** administrás tus bicis y sus componentes.

- **AGREGAR BICI:** nombre y notas opcionales. Podés renombrar, archivar,
  restaurar o borrar (con confirmación). Los rides **conservan** su
  referencia a la bici aunque la borres (referencia histórica suave).
- **Componentes:** por cada bici agregás cadena, suspensión, neumáticos o
  frenos, con su **ciclo de vida** en km, horas y días (completá al menos
  uno). Hay defaults: cadena 2000 km, suspensión 100 h, neumáticos 3000 km.
  **Frenos no tienen default**: tenés que configurarles el ciclo en km.
- **Alertas:** el motor de desgaste (ADR-004) marca cada componente con
  **«Próximo al cambio»** al 80% y **«Cambio vencido»** al 100% del ciclo
  de vida, calculado por el máximo normalizado de los tres ejes. Los
  **defaults son defaults, no verdades de fábrica** — ajustalos a tu bici.
- **REGISTRAR SERVICIO:** cuando cambiás un componente, registrá el
  servicio: resetea el acumulado y guarda un snapshot previo (km/horas
  consumidos, fecha, ratio) en el historial de mantenimiento.
- Solo los rides **asignados a esa bici** acumulan desgaste hacia sus
  componentes.

---

## Sync opcional (a tu propio NAS / Hermes Agent)

El sync está **apagado por defecto**. Solo se activa si lo configurás vos.

- **Qué es:** enviar cada ride terminado, vía webhook firmado por **HTTPS**,
  a un receptor **tuyo** (un NAS con Hermes Agent, Home Assistant, n8n,
  cualquier endpoint compatible). La app no tiene servidor propio: el
  destino lo definís vos.
- **Qué se envía:** métricas agregadas (distancia, moving time, velocidades,
  desnivel, calorías con su **banda** y **modelo**) + **waypoints
  decimados** (máx. 1 punto cada 10 s, tope de 2000). **Nunca** el track GPS
  crudo, el perfil corporal ni identificadores de dispositivo.
- **Cómo configurarlo:** en **Ajustes**, completá el **endpoint HTTPS**, la
  **health check URL HTTPS** (obligatoria y distinta del endpoint) y pegá
  el **secreto HMAC** que generaste en tu NAS (32+ bytes). La app guarda el
  secreto sellado en el Keystore del teléfono; nunca lo muestra de vuelta
  ni lo envía por la red. Tocá **Probar conexión** para validar.
- **Cómo funciona:** los rides van a una **outbox local** y un worker de
  WorkManager los envía cuando hay red, con reintentos y backoff
  exponencial. El payload va firmado con HMAC-SHA256; un mismo ride no se
  duplica.
- **Cómo desactivarlo:** botón **«Desconectar»** en Ajustes. Cancela el
  sync y **borra el secreto, la cola de envíos pendientes y la configuración
  del endpoint** (incluida la URL de health check). Los rides locales no se
  borran. El contrato completo del protocolo está en
  [`docs/sync-contract.md`](sync-contract.md); el *por qué* en
  [ADR-003](adr/ADR-003-hermes-agent-webhook-hmac.md).

---

## Qué no hace BuzzBike (por diseño)

- No te pide cuenta ni sincroniza con la nube de nadie.
- No envía analytics ni crash reports.
- No muestra calorías «exactas»: siempre rango + modelo.
- No distribuye por Play Store: las releases son APK en GitHub Releases.

Privacidad completa: [`PRIVACY.md`](../PRIVACY.md). Estado real del
proyecto: [`AGENTS.md`](../AGENTS.md) §0.
