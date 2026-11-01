# ADR-007: Perfil corporal — dato personal persistido en Room cifrado

- Estado: Aceptada
- Fecha: 2026-11-01
- Relacionados: ADR-001 (modelo calórico), ADR-003 (sync HMAC y minimización de datos)
- Alcance: `data/` (Room cifrado, DAO, mapper), `domain/` (perfil, `hasProfile`, snapshot por ride), `ui/` (Ajustes, historial, detalle), `sync/` (exclusión del payload)

---

## Contexto

### El problema

El perfil corporal de BuzzBike son cuatro campos: **peso, altura, edad y sexo**.
Cada uno por separado es dato personal y, **combinados**, el cuarteto funciona
como **cuasi-identificador**: en una población grande, la terna
peso/altura/edad más el sexo distingue a una persona con precisión suficiente
para reidentificarla cuando se cruza con otras fuentes. Además permite
**inferir datos de salud** (IMC, rango de peso, metabolicidad esperada), así
que tratarlo como "un ajuste de UI más" sería incorrecto: su almacenamiento es
una decisión de privacidad, no solo su transmisión.

A la vez, el modelo calórico lo necesita para dar el salto de precisión que el
proyecto declara:

- **ADR-001 §5.1** — Mifflin-St Jeor requiere los cuatro campos; sin ellos la
  ecuación no se aplica y nada se imputa (§5.3.3: no se imputa edad ni sexo).
- **ADR-001 §7** — el perfil es lo que mueve la banda declarada de
  **±30–40% (sin perfil)** a **±20–30% (con perfil)**.
- **ADR-001 §4** — con perfil completo, las paradas se tasan con `REE / 1440`
  en lugar de `1.0 MET`.

Sin persistencia, cada arranque de la app perdería el perfil y el usuario
volvería a la banda sin perfil.

### Evidencia y restricciones

- **El roadmap decía "DataStore + perfil corporal"** (macroplan
  `docs/roadmap.md` §0, Fase 2). Este ADR documenta el **cambio** y su motivo;
  esa línea se actualiza en el roadmap.
- **DataStore no está cifrado** por defecto. Guardar el perfil ahí lo habría
  dejado **fuera de la frontera de cifrado de los rides**: la base de Room ya
  está cifrada con SQLCipher y su passphrase está sellada en Android Keystore
  (ADR-003). El único dato personal en claro del proyecto sería justamente el
  más sensible.
- **ADR-001 §5.3.4/§5.3.5** — el perfil se puede modificar y borrar sin romper
  rides históricos, y es dato local que **no** se envía a Hermes ni a terceros.
- **ADR-003** — el payload de sync se limita a métricas agregadas + waypoints
  decimados (data minimization).
- **AGENTS.md §7** — nada sale del dispositivo sin acción explícita; sin
  analytics, sin telemetría, sin cuentas.

---

## Decisión

1. **Se persiste en Room cifrado con SQLCipher, no en DataStore.**
   El perfil vive en la tabla `user_profile` de **fila única**, dentro de la
   misma `AppDatabase` que `rides`, `track_points` y `outbox`, y por lo tanto
   **comparte exactamente la misma frontera de cifrado** (SQLCipher +
   passphrase sellada en Android Keystore, ADR-003). *Motivo del cambio
   respecto del roadmap:* DataStore/SharedPreferences no está cifrado, así que
   el perfil habría quedado fuera de la misma frontera de cifrado que los
   rides, y además introduciría una segunda fuente de verdad para el mismo
   dato. Esta ADR reemplaza la línea "DataStore + perfil corporal".

2. **Nunca sale del dispositivo.**
   - **No** se incluye en el payload de sync a Hermes: ese payload sigue
     siendo solo métricas agregadas y waypoints decimados (ADR-003).
   - **No** hay telemetría ni analytics que lo transporten (no existen en el
     proyecto) **ni logs que lo impriman**.

3. **Cada ride persiste un snapshot de los 4 campos usados en el cálculo.**
   Las columnas `profile_weight_kg`, `profile_height_cm`, `profile_age_years`
   y `profile_is_male` en `rides` congelan los inputs con los que se estimó
   cada ride. Cumple ADR-001 §5.3.4: **borrar o editar el perfil no rompe los
   rides históricos**, porque historial y detalle leen el snapshot del ride, no
   el perfil vivo.

4. **`hasProfile` = perfil completo.**
   `hasProfile` es `true` solo con los 4 campos presentes y utilizables (peso,
   altura y edad > 0, y sexo definido). Con perfil **parcial** se usa la banda
   **sin perfil** (±30–40%): no se inventa un estado intermedio entre "con
   perfil" y "sin perfil".

5. **Sin peso se etiqueta `supuesto: 70 kg`.**
   Si el producto usa los 70 kg del *reference man* histórico, la UI lo
   etiqueta explícitamente como `"supuesto: 70 kg"` (ADR-001 §5.3.2); nunca se
   presenta como un dato del usuario.

---

## Consecuencias

- **Borrar la fila `user_profile` degrada los cálculos futuros** a la banda sin
  perfil (±30–40%) y al baseline de paradas `1.0 MET`. Es una degradación
  controlada, no pérdida de datos.
- **El snapshot histórico permanece.** Es un dato del ride (con qué inputs se
  calculó), no del perfil vivo: se conserva aunque se borre o se edite el
  perfil, y por eso el detalle puede seguir mostrando el perfil real de ese
  ride.
- El perfil hereda el mismo cifrado y la misma passphrase que el resto de la
  base: un volcado de la DB sin la clave no expone ni rides ni perfil.
- **Riesgo residual:** quien tenga el dispositivo desbloqueado **y** la clave
  puede leer el perfil. Ese escenario no lo resuelve el cifrado de disco; se
  apoya en el Android Keystore (ADR-003), que impide extraer la passphrase del
  almacenamiento. No se añade gate biométrico en v1.
- **Migraciones:** el perfil entra con la migración 4→5 (la DB queda en
  **v5**); U4 agregará las tablas de garaje con **5→6**, no con 1→2.
- La UI debe seguir ADR-001 §8: con perfil parcial mostrar qué campo falta en
  vez de fingir perfil completo, y mantener siempre rango + modelo + inputs.

---

## Alternativas descartadas

### DataStore (plan original del roadmap)
**Descartada.** DataStore no cifra su almacenamiento: el perfil habría quedado
fuera de la frontera de cifrado que protege a los rides, con dos
almacenamientos distintos para datos del mismo usuario y dos fuentes de verdad
que podían divergir.

### Guardar el perfil solo en memoria
**Descartada.** Se pierde al morir el proceso: el usuario lo recompondría en
cada sesión y cada ride se calcularía sin perfil, anulando la banda ±20–30% y
el baseline `REE/1440` que motivan el perfil.

### Enviarlo al NAS (Hermes Agent)
**Descartada.** Viola la minimización de datos de ADR-003 (solo métricas
agregadas + waypoints decimados) y AGENTS.md §7 (nada sale del dispositivo sin
acción explícita del usuario). Además no aporta nada: el cálculo calórico corre
en el teléfono.

---

## Estado

**Aceptada — 2026-11-01.** Implementada en el slice **U3 (Ajustes y perfil)**:
tabla `user_profile` en Room cifrado, snapshot de perfil por ride, `hasProfile`
cableado en ride en vivo, historial y detalle, y Mifflin-St Jeor operativo como
baseline de paradas (ver la actualización 2026-11-01 de ADR-001).
