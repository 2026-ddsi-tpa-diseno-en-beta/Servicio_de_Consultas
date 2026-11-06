# Servicio de Consultas · Entrega 6

Java 21 / Spring Boot. Materializa eventos de los cuatro módulos en MongoDB y resuelve reportes sin llamar a sus APIs. Los documentos se diseñan para las consultas integradas.

## Configuración

```text
MONGODB_URI=<URI con nombre de base>
RABBITMQ_URL=<URI del broker, externa a Git>
READ_MODEL_MESSAGING_ENABLED=true
READ_MODEL_QUEUE=donatrack.readmodel.v1.events
READ_MODEL_ADMIN_KEY=<clave externa para inicialización/reconstrucción>
PORT=8085
```

Se usa una cola versión 1 nueva para incorporar la configuración de DLQ sin redeclarar la cola anterior con argumentos diferentes. Desplegar el consumidor y declarar la cola antes de activar publishers. Mantener una única réplica del materializador en esta entrega.

## API

Las rutas propias no llevan `/consultas`; el gateway las expone en `/api/consultas`.

| Ruta GET | Resultado |
|---|---|
| `/donadores/{id}/ficha` | Ficha integral de los cuatro módulos |
| `/necesidades/urgentes` | Faltantes, urgencia, stock por depósito y reservas pendientes |
| `/depositos/reporte` | Ocupación, algoritmo, asignaciones, entidades atendidas y tiempos por rango |
| `/calidad` | Agregación por categoría/subcategoría y ranking de quejas |
| `/estado` | Estado y cantidades del modelo de lectura |
| `/recursos/{tipo}` y `/recursos/{tipo}/{id}` | Snapshots diagnósticos de tipos conocidos |
| `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics` | Salud y métricas |

`POST /admin/reconstruir` requiere `X-Admin-Key` y reconstruye los reportes desde los snapshots. Para los datos anteriores al despliegue, cada módulo tiene `POST /admin/read-model/republicar` protegido por la misma variable. No se permite que MCP borre o regenere datos masivamente.

El [modelo de lectura](MODELO_LECTURA.md) especifica colecciones, contrato de eventos, fórmulas, filtros, versiones, recuperación y límites operativos.

## Validación

`mvn test` ejecuta reportes contra un servidor Mongo compatible en memoria, incluyendo duplicados, desorden, bajas y reconstrucción. `testing/entrega6` contiene el entorno real con MongoDB/RabbitMQ y el ensayo por gateway y MCP.

Logs: Better Stack opcional mediante variables externas. Las claves no se incluyen en los snapshots ni las respuestas de estado.
