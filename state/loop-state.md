# Loop State — BuzzBike

> Bitácora de auditoría del pipeline. Actualizado por el orchestrator en cada iteración.
> Formato de commits: Conventional Commits (ver AGENTS.md).

---

## Iteration 0 — 2026-10-02 — Discovery / Scoping

### Tarea
Inicio de proyecto greenfield: app Android open source (MIT) de trackeo de rutas
de bicicleta con GPS + sensores inerciales, cálculo de gasto calórico,
tracking de desgaste de componentes, y sync opcional a un agente de IA local
(Hermes Agent de Nous Research) vía webhook.

### Estado
**EN CURSO — scope aprobado por el usuario (2026-10-02).** Slices 1–8 approved.
Gate obligatorio por slice: `tester: PASS` + `security-reviewer: PASS`.

### Criterio de aceptación (a confirmar por el usuario)
- (TODO) Ver `docs/adr/` cuando el usuario defina el scope.

### Entorno (verificado)
| Item | Estado |
|------|--------|
| Repo | `/home/notebook01/Proyectos/BuzzBike` — git inicializado, sin commits |
| SO | Zorin OS 17.3 (Ubuntu base) |
| JDK | **AUSENTE** — `java: command not found`, no hay `/usr/lib/jvm`, no hay sdkman |
| Android SDK | **AUSENTE** — `ANDROID_HOME` y `ANDROID_SDK_ROOT` vacíos |
| adb | presente (`/usr/bin/adb`) |
| Python | `/home/notebook01/anaconda3/bin/python3` |
| Disk libre | 20 GB en `/` (Android SDK + build ≈ 8–12 GB) |

**Bloqueante para cualquier slice de código:** falta JDK 17+ y Android SDK.

### Investigación delegada (3 researcher en paralelo, completada)

#### researcher #1 — Sensores Android + cálculo calórico
- FGS `health|location` es obligatorio para trackeo continuo en A14+.
- Sin `HIGH_SAMPLING_RATE_SENSORS`, Android 12+ capea accel/gyro/mag a **200 Hz**
  (`minDelay` mínimo 5000 µs). Para cadencia/proxy de vibración no hace falta más.
- `FusedLocationProviderClient` con `PRIORITY_HIGH_ACCURACY` basta para v1.
  **Raw GNSS / PPP: NO para MVP** — el propio white paper de Google dice que sin
  correcciones externas el resultado puede ser peor que el del chipset.
- Activity Recognition (`ON_BICYCLE`) sirve como gate de sesión.
- Barómetro = delta de altitud relativo mucho más confiable que altitud GPS
  (que es pésima en vertical). Vertical gain requiere suavizado/histerésis.
- **Calorías — hallazgo central:**
  - Keytel 2005 (pub. validado): `r=0.913` con VO2max, `r=0.857` sin.
    Ecuación pública, reproducible.
  - Watches comerciales vs calorimetría: **error de EE 9–43%** (Wallen 2016, PLOS One).
  - Compendium 2024: MTB 8.5 MET, road 12–13.9 mph 8.0 / 14–15.9 mph 10.0 / >20 mph 16.8.
  - ACSM: `VO2 = 1.8*(W*6.12/masaKg) + 7` — requiere **potenciómetro**.
  - "35 kcal/km" **NO es una constante fisiológica**: es una heurística
    (≈3.5 MET × 70 kg × distancia). Ver objeciones más abajo.
  - MediaPipe Pose en background: NO viable (cámara + FGS `camera` + batería).
  - ANT+: solo en minoría de teléfonos; requiere dongle USB. Descartado del camino crítico.
  - BLE es el estándar: HR `0x180D`, CSC `0x1816`, Power `0x1818`.
- No existe paper público de Meta/Google sobre su algoritmo de calorías.
  **Prohibido inventar la cita.**

#### researcher #2 — Hermes Agent / integración webhook
- **Hermes Agent** (framework, NO el modelo) existe: https://github.com/NousResearch/hermes-agent
  — MIT, Python, con **webhooks HTTP nativos en puerto 8644** y **HMAC V2**
  (`X-Webhook-Signature-V2` = HMAC-SHA256 de `<timestamp>.<body>`, ventana ±300s).
  Tiene idempotencia por delivery ID (cache 1h), rate limit 30 req/min,
  body limit 1 MB, y `deliver_only: true` para notificar sin gastar tokens.
- El MODELO Hermes (Hermes 3 8B / Hermes 4.x) es otra cosa, open-weights en HF.
  Los docswns desalientan usar Hermes 4 dentro del agente (no es tool-calling).
- Patrón recomendado: **Outbox en SQLite + WorkManager → POST firmado**.
- Payload de un ride de 2h en JSON compacto + gzip ≈ 100–250 KB. Cabe en el
  límite de 1 MB sin chunking. Data minimization: mandar métricas + waypoints
  decimados, no el track crudo.
- Aviso dePrivacy: el idempotency cache de Hermes dura 1 h → el receptor debe
  dedupear por `ride.id` además del header.

#### researcher #3 — Mapas, formatos, wear tracking
- **MapLibre Native Android** (BSD) muy activo (v13.5.0, ago 2026). **Usar.**
- **osmdroid está ARCHIVADO** (nov 2024). No usar.
- **OpenFreeMap** — vector tiles, sin API key, sin límites. Requerido:
  atribución visible `© OpenStreetMap contributors` + `© OpenMapTiles`.
- **tile.openstreetmap.org prohíbe offline/prefetch explícitamente** y bloquea
  apps por User-Agent genérico. No usarlo como tile source.
- Google Maps / HERE / MapTiler: incompatibles con una app MIT open source.
- **Offline**: `pmtiles://file://$path` nativo en MapLibre ≥11.7.0.
  `pmtiles://asset://` NO funciona (hay que copiar a filesDir).
- **Formatos**: exportar **GPX 1.1 + TrackPointExtension** (hr/cad/dist).
  **FIT = licencia Garmin propietaria**, no usar como formato nativo.
- **Prior art de wear tracking**: existe `higorslva/biketrackd` (MIT, Android
  Kotlin Compose, MapLibre + OpenFreeMap, wear % + alerts) — casi idéntico al
  proyecto. Verificar antes de reinventar.
- No hay schema estándar de intercambio de componentes entre apps.

### Objeciones a las premisas del usuario (a discutir antes de codear)

1. **"cálculo exacto de calorías"** — no existe con传感oes de teléfono.
   El techo realista sin potenciómetro es ±10–20% (Keytel + MET fusionado).
   Con potenciómetro: ±5–10%. Con calorimetría: es otro proyecto.
   **El objetivo honesto es "el mejor estimador disponible y auditable,
   con la incertidumbre declarada en la UI"**, no "exacto".

2. **"usar la mayoría de sensores"** — más sensores ≠ más precisión.
   Aporta: HR (BLE strap) > potenciómetro > barómetro > GPS速度.
   NO aporta o actively harmful en background: magnetómetro (interferencia),
   Linear acceleration (derivado), Gravity (derivado).
   La IMU del teléfono sirve para *detectar que hay movimiento*, no para medir
   el esfuerzo fisiológico.

3. **"35 kcal/km"** — es una heurística de app genérica, no una constante.
   La dependencia real de la energía en ciclismo es **potencia × tiempo**
   (Watts → joules), no distancia. A 150 W constante vs 250 W constante, la
   distancia cambia pero el gasto calórico es el mismo. Un ride de 20 km en
   plano a 30 km/h y uno de 40 km a 15 km/h con la misma potencia gastan
   **lo mismo**.

4. **"backend local"** — el同意 con que el cálculo重度 (calorías, wear, stats)
   sea 100% on-device es total. Lo que va al agente es un **reporte**, no
   lógica de negocio.

5. **Mapas "freemaps"** — interpreto como mapas libres/open source.
   Confirmar si además quiere modo offline real (PMTiles) o alcanza con online.

### Decisiones de scope — CONFIRMADAS por el usuario (2026-10-02)
- [x] **D1. Sensores: NINGUNO.** El usuario no tiene HR strap ni potenciómetro.
      Pendiente futuro. → El slice 5 arranca en el nivel 3 de la cascada
      (Compendium MET por velocidad GPS). Techo de precisión **±25–35%**.
      La UI debe declarar el margen SIEMPRE, no solo en fallback.
      La arquitectura deja el hook para niveles 1 y 2 sin reescribir `domain`.
- [x] **D2. Hermes Agent corre en el NAS del usuario**, alcanzable desde el
      teléfono por Tailscale (WireGuard). → ADR-003 usa Tailscale como transporte.
- [x] **D3. Mapas: online primero.** MapLibre Native + OpenFreeMap.
      Offline PMTiles diferido (ADR-002). NO construir PMTiles en v1.
- [x] **D4. Distribución: GitHub Releases (APK).** NO Play Store, NO F-Droid en v1.
      Enfoque: cualquiera puede compilar; el APK es cortesía.
- [x] **D5. Toolchain: instalar en esta máquina.** Descarga manual a `~/tools/`
      (no hay sudo; no hay apt). JDK → Temurin 17 x64 desde Adoptium.
- [ ] **D6. Nombre del paquete / app id** — placeholder actual `com.buzzbike.app`.
      **PENDIENTE** — usar `com.buzzbike.app` salvo que el usuario diga otra cosa.

### Dependencia externa notable
Slice 7 (webhook HMAC) depende de que el usuario tenga el Hermes Agent
instalado y accesible por Tailscale. Si no está listo al momento del slice 7,
se implementa contra la spec documentada y se valida con un mock receiver
local. El secret HMAC en Android Keystore es **manejo de secretos** →
aprobación explícita del usuario requerida antes de que `builder` escriba código
(ESQUELADO DE ESCALADO del orchestrator).

### Iteraciones
- Total: 0 (discovery)
---

## Iteration 1 — 2026-10-26 — Slice 1 (scaffold) — INCOMPLETO / SIN VERIFICAR

### Cambio de proceso pedido por el usuario
El usuario pidió acelerar: **solo `builder` + `tester` por slice**, y un único
pase de `security-reviewer` al final como **gate de release**.
Aceptado con estas condiciones (registradas como deuda):

- **Deuda de seguridad acumulada**: slices 2–8 (y en particular el 7:
  secret HMAC + Keystore + red) no pasan por security review hasta el final.
- **Excepción**: si en el slice 7 aparece un hallazgo crítico, se revisa en el
  momento, no se espera al release.
- **Regla que NO se relaja**: ninguna fórmula de dominio físico se acepta sin
  fuente citada + ADR (AGENTS.md §6).
- La tarea **no se marca completa** hasta que `security-reviewer` dé PASS.

### Toolchain — COMPLETO
| Componente | Versión / ubicación |
|---|---|
| JDK | Zulu **17.0.13** en `~/tools/zulu17.54.21-ca-jdk17.0.13-linux_x64` |
| Gradle | **9.8.2** y 8.14.5 en `~/tools/` |
| Android SDK | **36** en `~/Android/Sdk` (platform-tools 37.0.1, platforms;android-36, build-tools;36.0.0) |
| Licencias | aceptadas |
| Env | `source /home/notebook01/tools/androidenv.sh` |
| Restricciones | sin sudo; red lenta; sin emulador ni dispositivo conectado |

### Bug de versiones encontrado y resuelto
La matriz inicial era **inválida**: `Hilt 2.60.1` exige `AGP >= 9.0.0` pero se
declaraba `AGP 8.13.2`. Error real al aplicar el plugin.

Resuelto con **Camino A** (documentado en `gradle/libs.versions.toml`):
AGP 9.4.1 · Gradle 9.8.2 · Kotlin 2.4.21 (solo plugin Compose) · KSP 2.3.12 ·
Hilt 2.60.1 · Room 2.8.5 · Compose BOM 2026.09.00 · lifecycle 2.11.0 ·
activity-compose 1.13.0 · navigation-compose 2.10.2 · hilt-nav 1.4.0 ·
MapLibre 13.7.1 · ktlint 14.2.0 · detekt 1.23.8.

**Datos técnicos a no olvidar:**
- KSP pasó a versionado independiente en 2.3.0; la última acoplada a Kotlin es
  `2.2.21-2.0.5`. **No existe KSP 2.4.x.**
- Con AGP 9 el plugin `org.jetbrains.kotlin.android` es un **error duro**
  (Kotlin viene embebido en AGP).

### Prior art — decisión
`higorslva/biketrackd` es **referencia, NO base**. Su wear es km-only sin
eventos de service ni reset (no cumple ADR-004), no tiene calorías, no tiene
sync, y tiene la atribución ODbL **deshabilitada** (issue #1 abierta) — riesgo
legal a heredar. Reutilizable con atribución: patrón OpenFreeMap +
OfflineManager, badges de alerta, semáforo de colores.
`gear-tracker-app` aporta los defaults de service de suspensión (Fox/RockShox)
que hay que citar en ADR-004 al implementar el slice 6.

### Estado del gate — PENDIENTE
| Comando | Estado |
|---|---|
| `./gradlew assembleDebug` | **Interrumpido en `:app:compileDebugKotlin`** (a pedido del usuario). Sin EXIT, sin APK. |
| `./gradlew test` | **NUNCA ejecutado** |
| `./gradlew ktlintCheck` | **NUNCA ejecutado** |
| `./gradlew detekt` | **NUNCA ejecutado** |
| `./gradlew lint` | **NUNCA ejecutado** |

**No hay ningún PASS registrado.**

### Violación de proceso detectada
El scaffold fue commiteado en `e54cce2 chore: add full project scaffold` **antes
de pasar ningún gate**. No es fatal (es solo scaffold y se corrige con commits
posteriores), pero queda registrado: a partir de ahora ningún slice se commitea
sin `tester: PASS` sobre el diff.

### Git
- `c7eddea docs: add project foundation and architecture decisions`
- `820fd53 chore: harden .gitignore and untrack docs/config/internal files`
- `e54cce2 chore: add full project scaffold (docs/config included)`

### Punto exacto de reanudación
1. `source /home/notebook01/tools/androidenv.sh`
2. `cd /home/notebook01/Proyectos/BuzzBike`
3. Correr los 5 comandos del gate y **arreglar lo que falle**.
   Sospechosos: DSL de AGP 9, y compatibilidad KSP 2.3.12 ↔ Kotlin 2.4.21.
4. `tester` valida el diff → si PASS, commit de correcciones.
5. Recién entonces: slice 2 (schema Room).

### Pendientes del usuario
- App id definitivo (`com.buzzbike.app` es placeholder).
- Copyright holder del LICENSE.
- Nombre definitivo de la app.
- Config del Hermes Agent (ruta de webhook) para el slice 7.
