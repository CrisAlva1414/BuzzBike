# ADR-001: Modelo de cálculo calórico v1

- **Estado:** Aceptado
- **Fecha:** 2026-10-02
- **Decisor:** Usuario (con realidad check del equipo de investigación)

---

## Context

BuzzBike debe calcular el gasto calórico de un ride usando el teléfono del usuario. Restricciones duras del proyecto:

- El usuario **no tiene** HR strap ni potenciómetro. Son pendientes futuros.
- El objetivo declarado por el usuario fue "cálculo exacto".
- El motor debe funcionar en v1 sin sensores externos, pero la arquitectura debe permitir enchufarlos después sin reescribir el domain.

**Reality check documentado (no es opinión, es evidencia):**

- Los relojes comerciales tienen un error de 9–43% vs calorimetría directa (Wallen 2016, PLOS One, DOI: `10.1371/journal.pone.0154420`).
- Sin sensores externos, el techo realista de precisión es **±25–35%**.
- La dependencia física real de la energía en ciclismo es **potencia × tiempo** (vatios → julios), **no distancia**. A 150 W vs 250 W constante, la distancia cambia pero el gasto calórico es el mismo. Un ride de 20 km en plano a 30 km/h y uno de 40 km a 15 km/h con la misma potencia gastan **lo mismo**.

Por lo tanto, "exacto" no es un objetivo alcanzable con un teléfono. El objetivo honesto es: **el mejor estimador disponible y auditable, con la incertidumbre declarada en la UI**.

---

## Decision

Implementar un **motor de cascada de 3 niveles**, en orden de disponibilidad y precisión:

| Nivel | Modelo | Condición de activación | Precisión esperada |
|---|---|---|---|
| 1 | **ACSM** — `VO2 = 1.8 × (W × 6.12 / masaKg) + 7` | Hay potenciómetro (BLE `0x1818`) | ±5–10% |
| 2 | **Keytel 2005** — ecuación pública con/sin VO2max | Hay HR strap (BLE `0x180D`) | r=0.857 sin VO2max |
| 3 | **Compendium of Physical Activities 2024** — METs por bandas de velocidad GPS | Sin HR ni potencia (caso v1) | ±25–35% |

**BMR de reposo:** Mifflin-St Jeor, usado como base para el componente sedentario del ride.

**Reglas de presentación en UI (obligatorias):**

1. La UI **DEBE** mostrar el intervalo de incertidumbre estimado del modelo usado.
2. La UI **DEBE** indicar qué modelo de la cascada se aplicó (p. ej. "estimado por velocidad (Compendium)").
3. **Nunca** presentar un número único como exacto. No decir "250 kcal" a secas; decir "≈250 kcal (±30%, modelo Compendium)".

**Reglas de código (obligatorias, ver AGENTS.md §6):**

- Toda constante o fórmula de este ADR se implementa en `domain/` con KDoc que cite la fuente.
- Cualquier cambio posterior a estas fórmulas requiere un ADR nuevo o la actualización de este, con la fuente citada.
- Prohibido inventar papers o constantes. Si falta fuente → `TODO:` + discusión, no código.

---

## Consecuencias

- **El usuario ve honestidad, no magia.** La app declara su incertidumbre en vez de fingir precisión. Esto es una feature de confianza, no un defecto.
- **En v1 (sin sensores), el nivel 3 es el camino crítico.** El code review del motor calórico se centra en que las bandas de velocidad del Compendium estén correctas y citadas.
- **La arquitectura queda abierta para sensores futuros.** Cuando el usuario consiga un HR strap, se activa el nivel 2 sin tocar la UI ni el dominio de descriptores — solo se añade la fuente de datos HR y el modelo Keytel ya presente.
- **Las fórmulas son knowledge, no trivia.** Cualquier PR que las cambie sin cita a fuente y sin ADR se rechaza. Esto protege el proyecto de "optimizaciones" no fundamentadas.
- **El error residual (±25–35%) se documenta en la UI y en la documentación del usuario.** No se esconde.

---

## Alternativas descartadas

### Heurística "35 kcal/km"
**Descartada.** No es una constante fisiológica: es una aproximación de app genérica (≈3.5 MET × 70 kg × distancia). Ignora que la energía depende de **potencia × tiempo**, no de distancia. Dos rides con la misma potencia pero distinta distancia gastan lo mismo; la heurística los califica mal. Además asume una masa corporal fija implícita.

### ML on-device en v1
**Descartado.** Complejidad no justificada para v1: requiere dataset etiquetado, entrenamiento, modelo embebido, y aún así no superaría el techo de los datos de entrada (sin HR ni potencia, el techo es ±25–35% aunque el modelo sea perfecto). Se puede reconsiderar si en el futuro hay sensores de calidad y datos de calorimetría propios.

### Presentar un único número "sin ruido"
**Descartado.** Irresponsable desde el punto de vista de producto y de honestidad científica. Los wearables comerciales ya cometen este error (Wallen 2016); BuzzBike no lo va a repetir.

### Usar solo Compendium aunque haya HR
**Descartado.** Si el usuario en el futuro conecta un HR strap, la ecuación de Keytel (con datos reales de HR) es objetivamente mejor estimador que las bandas de velocidad. La cascada existe justamente para mejorar cuando hay mejores inputs.

### Inventar una "fórmula propia mejorada"
**Descartada.** Prohibido por regla del proyecto (AGENTS.md §6). Sin calorimetría de referencia propia, no hay forma de validar una fórmula nueva. Se usan las publicadas y auditables.

---

## Fuentes

| Fuente | Referencia | Uso |
|---|---|---|
| Keytel et al. 2005 | https://www.tandfonline.com/doi/full/10.1080/02640410470001730089 | Nivel 2 — ecuación calorías con/sin VO2max |
| Compendium of Physical Activities 2024 | https://pacompendium.com/bicycling/ | Nivel 3 — METs por banda de velocidad |
| ACSM Cycle METs | https://exrx.net/Calculators/CycleMETs | Nivel 1 — VO2 a partir de potencia |
| Mifflin-St Jeor | Estándar clínico de BMR | Componente de reposo |
| Wallen et al. 2016, PLOS One | DOI: `10.1371/journal.pone.0154420` | Reality check de error de wearables (9–43%) |
