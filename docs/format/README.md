# docs/format — Convenciones de formato y estilo

Índice de las convenciones de código del proyecto BuzzBike.

Estas reglas complementan a `AGENTS.md`. Si hay conflicto, `AGENTS.md` manda en lo que sea arquitectura/dominio; este directorio manda en estilo y formato de código.

| Archivo | Qué cubre |
|---|---|
| [`estilo-kotlin.md`](./estilo-kotlin.md) | Convenciones Kotlin/Compose: naming, estructura de Composables, null safety, sealed classes para estados de UI, coroutines/Flow. |

## Herramientas

| Herramienta | Rol | Comando |
|---|---|---|
| **ktlint** | Formato oficial de Kotlin (estilo de la comunidad/kotlinlang) | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| **detekt** | Análisis estático: complejidad, code smells, reglas propias | `./gradlew detekt` |

Config de ambas herramientas (`.editorconfig`, `detekt.yml`, config de ktlint en Gradle) se define cuando se genere el wrapper de Gradle — **TODO:** crearla en el slice de setup.

## Regla general

Antes de cada PR o commit a `main`:

```bash
./gradlew detekt ktlintCheck test assembleDebug
```

Si ktlint o detekt fallan, se arregla el código — no se desactivan reglas para pasar el verde, salvo justificación documentada en el ADR o en el mensaje de commit.
