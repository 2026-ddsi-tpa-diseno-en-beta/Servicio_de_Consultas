# DonaTrack Servicio de Consultas (CQRS)

Servicio Java 21 / Spring Boot que materializa eventos en MongoDB y responde consultas solo contra el modelo de lectura. No debe consultar los cuatro módulos al atender una consulta.

## Endpoints

- `GET /consultas/estado`: estado básico y colecciones `rm_*`.
- `GET /consultas/recursos/{tipo}?limite=100`: lista una proyección.
- `GET /consultas/recursos/{tipo}/{id}`: recupera una proyección.
- `GET /consultas/estadisticas/{tipo}`: pipeline de agregación MongoDB (`$group` + `$project`) sobre el campo `estado`.
- `GET /actuator/health`, `/actuator/prometheus`, `/actuator/metrics`.

## Contrato de eventos RabbitMQ

La cola configurable (`READ_MODEL_QUEUE`, por defecto `donatrack.readmodel.events`) recibe JSON:

```json
{
  "eventId": "uuid-unico",
  "source": "donaciones",
  "resourceType": "productos",
  "resourceId": "123",
  "operation": "UPSERT",
  "occurredAt": "2026-10-11T15:00:00Z",
  "payload": { "nombre": "Arroz", "estado": "ACTIVO" }
}
```

- `eventId` permite deduplicar entregas repetidas.
- La proyección se guarda con `_id=resourceId` mediante upsert.
- Se compara `ultimaActualizacion` para no aplicar una instantánea anterior a otra ya persistida.
- El modelo de lectura es una copia de consulta; los módulos siguen siendo dueños de los datos.
- Si el evento se consume pero falla la escritura, Rabbit reintentará según la configuración de la cola/broker. Configurar DLQ y alertas en el broker para producción.

## Importante para el despliegue integrado

Este servicio es la base ejecutable del lado de lectura. Para que los datos se poblen, los cuatro componentes deben publicar eventos con el contrato anterior en la misma cola/exchange y tras confirmar su escritura. El ZIP de Entrega 5 no tenía un contrato común de eventos de cambios para los cuatro módulos; por eso no se inventa una sincronización por polling ni se afirma que las cuatro consultas complejas ya estén alimentadas. La documentación de despliegue incluida señala la integración pendiente por componente y las variables necesarias.

Las cuatro consultas de negocio de la consigna requieren proyecciones específicas y eventos suficientes (donadores, categorías, quejas, donaciones, asignaciones, necesidades, depósitos, misiones e insignias). El endpoint de agregación es funcional sobre los datos proyectados, pero no sustituye por sí solo esas cuatro consultas.

## Logs y Grafana

- Better Stack: `BETTERSTACK_SOURCE_TOKEN`, opcional `BETTERSTACK_INGEST_URL`, `APP_NAME=donatrack-servicio-consultas`.
- Importar `../observability/grafana-donatrack-consultas.json` en Grafana y seleccionar el datasource Prometheus. El dashboard del gateway está en `../observability/grafana-donatrack-gateway.json`.
