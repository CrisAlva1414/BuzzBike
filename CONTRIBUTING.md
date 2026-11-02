# Contributing — BuzzBike

Gracias por querer meter mano. Este proyecto es local-first, MIT, sin
backend y sin telemetría: cualquier cambio que rompa eso se rechaza en
review. Este documento es la lista corta de cómo se trabaja acá.

---

## 1. Setup

Seguí [`docs/setup-entorno.md`](docs/setup-entorno.md): guía copiar-y-pegar
para instalar JDK + Android SDK en una máquina Linux **sin sudo** (todo bajo
`$HOME`), con los pitfalls del toolchain.

Resumen de versiones esperadas (fuente de verdad: `gradle/libs.versions.toml`
y `AGENTS.md` §4):

- **JDK 21** (probado: Temurin 21.0.13; el CI usa JDK 21)
- **Android SDK 37** (`compileSdk 37` / `targetSdk 36` / `minSdk 26`)
- Gradle **9.8.2** vía wrapper del proyecto — no instalarlo a mano

Después de instalar, leé `AGENTS.md` §0 antes de tocar código.

---

## 2. Gate obligatorio

Ningún PR va en verde sin esto:

```bash
./gradlew test ktlintCheck detekt assembleDebug lintDebug --console=plain
```

Y si el cambio toca permisos, foreground service, SQLCipher o comportamiento
que solo se valida con hardware:

```bash
./gradlew assembleDebugAndroidTest --console=plain   # compila instrumentados
./gradlew connectedDebugAndroidTest                   # los ejecuta (requiere emulador)
```

**Emulador local:** el AVD **`tester36`** (system image
`android-36;google_apis;x86_64`) está en `~/android-sdk/emulator`. Para
levantarlo:

```bash
~/android-sdk/emulator/emulator -avd tester36
```

El CI (`.github/workflows/android.yml`) corre el gate JVM en cada push/PR y
sube el APK de debug como artifact `buzzbike-debug-apk`; **no** ejecuta los
instrumentados.

Detalle de qué testea qué: [`docs/testing.md`](docs/testing.md).

---

## 3. Reglas del proyecto (no negociables)

1. **Conventional Commits**, en inglés, presente imperativo, sin punto
   final. Tipos: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`,
   `chore`. Scope entre paréntesis opcional (`feat(domain): …`). El body
   explica el **por qué**, no el qué. Commiteá directo, sin pedir
   confirmación del mensaje.

2. **Dominio físico con fuente + ADR.** Toda fórmula de calorías, BMR, MET
   o desgaste **debe** citar su fuente en el código (KDoc) y en su ADR.
   Un diff que cambie una constante fisiológica sin ADR se rechaza. No
   inventes papers, constantes ni URLs: si no hay fuente publicada, se marca
   `TODO:` y se discute. Fuente canónica v1: `AGENTS.md` §6 (Compendium
   2024, Keytel 2005, Mifflin-St Jeor, Wallen 2016).

3. **UI de calorías siempre como rango.** La app **nunca** muestra un
   número calórico como exacto: siempre intervalo de incertidumbre
   (±30–40% sin perfil / ±20–30% con perfil) **y** el modelo de la cascada
   aplicado. «≈250 kcal (±30%, modelo Compendium)» es correcto; «250 kcal» a
   secas no. Ver ADR-001.

4. **Local-first.** Nada sale del dispositivo sin acción explícita del
   usuario. Sin cuentas, sin analytics, sin crash reporting con PII, sin
   publicidad. Si agregás una dependencia que hable con la red, tiene que
   ser opt-in y documentada en [`PRIVACY.md`](PRIVACY.md).

5. **Sin telemetría.** Cero SDKs de medición de uso. Los logs no llevan
   URLs de sync ni secretos.

6. **HTTPS-only.** `cleartextTrafficPermitted="false"` se mantiene. Si un
   destino legado solo habla HTTP, se resuelve con un ADR — no relajando
   `network_security_config.xml`.

7. **Privacy:** los tracks GPS son datos sensibles (GDPR). Minimización de
   datos en todo lo que salga del dispositivo (ver [`PRIVACY.md`](PRIVACY.md)
   y ADR-003).

8. **Ramas:** rama de integración `main`. `git pull` antes de empezar y
   antes de push. En fase greenfield se commitea directo en `main`; PRs
   cuando haya colaboradores.

---

## 4. Tests de migración

Si un slice cambia el schema de Room (nueva tabla, columna, índice), hay
que:

1. **Actualizar el schema exportado** (`app/schemas/…/N.json` —
   `exportSchema = true`).
2. **Agregar la migración** en `app/src/main/java/com/buzzbike/app/data/local/Migrations.kt`
   (ej. `MIGRATION_5_6`).
3. **Agregar un test en `MigrationsTest.kt`**
   (`app/src/test/java/com/buzzbike/app/data/local/MigrationsTest.kt`) que
   siga el patrón existente:
   - crear la base en la versión **anterior** con el SQL exacto (copiar de
     la sección `V…_` del propio test o del schema JSON anterior);
   - insertar filas que deban **sobrevivir** a la migración;
   - abrirla con `Room.databaseBuilder(...).addMigrations(...)` para que
     Room valide el schema resultante durante `onUpgrade`;
   - assert de tablas/columnas/índices nuevos y de que las filas
     existentes siguen legibles;
   - si la migración puede corromper datos (p. ej. drops), assert de que
     las filas viejas no desaparecen.
4. `room-testing` **no** es dependencia del proyecto: las bases fuente se
   crean a mano con SQL, como ya hacen los tests existentes
   (`given_v4Database_when_migratedToV5_…`).

La DB actual está en **v6** (migraciones 1→2 … 5→6). La próxima sería
**6→7**, solo si un slice la necesita.

---

## 5. Cómo se cierra un slice

1. **`tester: PASS` explícito.** Ningún slice se cierra sin PASS del
   tester. Si el gate falla, se arregla la causa raíz antes de darlo por
   cerrado.
2. **`security-reviewer: PASS`** si el slice toca **secretos o red**
   (sync, Keystore, HMAC, endpoints) — y **siempre** como gate final de
   release. La deuda de seguridad histórica está registrada en el roadmap
   §4; un hallazgo **crítico** se revisa en el momento, sin esperar al
   release.
3. El detalle fino del pipeline vive en `state/loop-state.md`
   (bitácora, **no editar a mano** — la actualiza el orchestrator).

---

## 6. Docs

Si cambia un contrato, un ADR o el estado real, actualizá en el mismo PR:

| Qué | Dónde |
|---|---|
| Estado y cómo retomar | `AGENTS.md` §0 |
| Por qué el sistema es así | `docs/adr/` (ADR nuevo o actualización del existente) |
| Estado del producto | `docs/roadmap.md` |
| Contrato de sync | `docs/sync-contract.md` |
| Privacidad | `PRIVACY.md` |
| Cambios notables | `CHANGELOG.md` (sección `[No publicado]`) |

---

## 7. Ideas y feedback

No sabés codear pero tenés ojo de ciclista, de datos o de self-hosting:
abrí un issue igual. Lo que más sirve está en el `README.md` §«Ideas y
feedback».
