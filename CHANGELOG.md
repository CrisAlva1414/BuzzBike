# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto adhiere a [Versionado Semántico](https://semver.org/lang/es/).

## [No publicado]

### Añadido

- Pantalla de ride en vivo con estados de reposo, listo, grabando, pausado,
  terminado y recuperable.
- Resumen del ride al terminar, con distancia, velocidad, duración, desnivel y
  banda calórica.
- Recuperación de un ride interrumpido: al reabrir la app se puede salvar o
  descartar sin perder los datos capturados.
- Métricas en vivo con banda de incertidumbre calórica y modelo aplicado
  (ADR-001).
- Solicitud del permiso de ubicación en runtime desde la pantalla de ride, con
  acciones y mensajes para permiso denegado y ubicación aproximada.

### Cambiado

- La sesión de ride usa un contrato sellado que serializa todas las
  transiciones y evita estados inválidos.
- La auto-pausa se deriva de la detección de movimiento existente (ADR-005),
  sin umbrales nuevos.

### Corregido

- La instalación limpia dejaba «INICIAR RIDE» deshabilitado para siempre
  porque nadie solicitaba el permiso de ubicación.
