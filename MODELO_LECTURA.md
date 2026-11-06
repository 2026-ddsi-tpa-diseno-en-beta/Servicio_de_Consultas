# Modelo de lectura de DonaTrack

## Responsabilidades y límites

Los cuatro módulos son dueños de sus datos. Cada cambio genera un snapshot en su outbox transaccional. Un relay lo publica en RabbitMQ después del commit y conserva el pendiente ante errores. Consultas recibe el evento, actualiza el snapshot si su versión es mayor y reconstruye los reportes afectados usando exclusivamente MongoDB.

Se conserva el origen y la versión por recurso. Cada tipo tiene un único módulo dueño. Un borrado deja un tombstone: mensajes viejos no pueden revivir el recurso ni eliminar una versión nueva. La entrega del broker es al menos una vez; el consumidor es idempotente. No hay transacción distribuida entre PostgreSQL, RabbitMQ y MongoDB.

Para migrar el prototipo existente, un snapshot sin `version` puede ser reemplazado por el primer evento versionado aunque su etiqueta `fuente` anterior fuera genérica. Luego no se permiten cambios de fuente. No hace falta cambiar la URI de MongoDB por esta migración.

La actualización del snapshot y sus reportes no es una única transacción Mongo. El evento solo se marca procesado después de terminar las proyecciones. Si falla, el reintento reconstruye desde el snapshot vigente. Pueden observarse estados intermedios mientras se aplica un cambio: consistencia eventual.

## Contrato versión 1

```json
{"schemaVersion":1,"eventId":"uuid-estable","source":"donaciones","resourceType":"donacion","resourceId":"123","operation":"UPSERT","version":42,"occurredAt":"2026-11-06T12:00:00Z","payload":{"donadorID":"12","productoID":"3","cantidad":10,"estado":"INGRESADA"}}
```

`version` es el ID monotónico del outbox de la fuente, conservado durante reintentos. `eventId` identifica el envío lógico. No se comparan relojes de máquinas distintas. El estado persistido y sus IDs no se reinician manteniendo una Mongo vieja: una reconstrucción de entornos debe coordinar las bases. Un cambio de `source` requiere migración, no renombrar el servicio sin actualizar el modelo.

## Colecciones

| Colección | Contenido / eventos |
|---|---|
| `rm_donador`, `rm_entidad`, `rm_necesidad`, `rm_queja` | Snapshots de Donadores y Entidades |
| `rm_donacion`, `rm_producto`, `rm_categoria` | Snapshots de Donaciones |
| `rm_deposito`, `rm_paquete`, `rm_asignacion` | Snapshots de Logística |
| `rm_incentivo_donador`, `rm_mision`, `rm_insignia` | Snapshots de Incentivos |
| `q_donadores` | Ficha enriquecida, cantidades de donaciones por estado, incentivos y entregas a entidades |
| `q_necesidades` | Entidad, producto/clasificación, faltante, stock por depósito y asignaciones pendientes |
| `q_depositos` | Capacidad/ocupación, algoritmo, estados y datos de entregas para filtrar fechas |
| `q_calidad` | Donaciones enriquecidas con categoría y subcategoría para agregación |
| `read_model_processed_events` | IDs de eventos terminados; evita repetir trabajo |

Los reportes se actualizan por dependencias: una asignación afecta a su necesidad, depósito y donador; un cambio de producto o categoría refresca sus donaciones y necesidades; las bajas eliminan reportes y conservan los tombstones originales. Se crean índices para las relaciones y filtros de uso frecuente. No se vuelven a sumar deltas al recibir un duplicado.

## Semántica de consultas

- `GET /donadores/{id}/ficha`: devuelve 404 si todavía no llegó el donador o fue eliminado. Incluye el historial real que conserva el módulo; no se inventan fechas de categorías antiguas.
- `GET /necesidades/urgentes`: `urgenciaMinima` (1–10), `tipo`, `categoriaID`, `subcategoriaID`, `limite` (1–500). Orden por urgencia descendente y luego ID. Faltante = objetivo menos recibido, nunca menos que cero. `cantidadAsignada` del módulo significa recibido: se presenta como `cantidadRecibida`. Las reservas se calculan aparte con todas las asignaciones `ASIGNADA`, incluso si se crearon antes del período actual: siguen pendientes de entrega. Stock disponible usa solo paquetes `EN_STOCK`; los `PENDIENTE` todavía no completaron matchmaking.
- `GET /depositos/reporte`: `depositoID`, `desde`, `hasta`. El rango se aplica a la fecha de ingreso. Se cuenta una donación una vez por depósito y se usa su última entrega registrada. Sin entregas, el promedio es nulo. Ocupación se expresa como proporción 0–1; capacidad cero da nulo. Fechas sin zona siguen la zona de las APIs, configurada como America/Argentina/Buenos_Aires en el entorno local.
- `GET /calidad`: `limiteDonadores` (1–500). Pipeline `$group`, `$addFields`, `$sort`. Proporción = cantidad de donaciones `CONQUEJA` / total de donaciones de la categoría/subcategoría; no unidades. El ranking solo incluye donadores con quejas y conserva su estado/categoría del módulo dueño.
- `GET /estado`: cantidades de proyecciones y eventos procesados. No prueba la salud de los otros módulos; MCP tiene herramientas de salud separadas.

Los endpoints se exponen por el gateway con prefijo `/api/consultas`. Las lecturas genéricas `/recursos/{tipo}` son diagnósticas y no sustituyen los reportes funcionales.

## Inicialización y recuperación

1. Desplegar Consultas, Mongo y broker; declarar la cola `donatrack.readmodel.v1.events` con DLX y DLQ. La cola anterior no tiene los mismos argumentos; no redeclararla con otra configuración.
2. Desplegar los publishers con confirmaciones correlacionadas, devoluciones y envío obligatorio. Activar `READ_MODEL_PUBLISHER_ENABLED=true` en los módulos y `READ_MODEL_MESSAGING_ENABLED=true` en Consultas.
3. Con `READ_MODEL_ADMIN_KEY` y header `X-Admin-Key`, invocar `POST /admin/read-model/republicar` en cada módulo. Reemite datos propios con versiones nuevas.
4. Esperar convergencia; consultar los cuatro reportes. `POST /admin/reconstruir` en Consultas reconstruye las proyecciones desde los snapshots, sin llamar a los módulos. Ambos endpoints administrativos requieren clave configurada y quedan fuera del catálogo MCP.
5. Ante errores, revisar la DLQ y la causa antes de reenviar. No purgar mensajes para que desaparezca el indicador.

El materializador se opera como una réplica con un consumidor serial en esta entrega. Para escalar a múltiples réplicas se requiere particionar por agregado o coordinar las proyecciones. También corresponde definir retención del outbox enviado y del registro de deduplicación según el período de replay acordado.
