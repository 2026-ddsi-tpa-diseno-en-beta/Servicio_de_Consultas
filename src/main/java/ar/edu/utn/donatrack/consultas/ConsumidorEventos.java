package ar.edu.utn.donatrack.consultas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.ReplaceOptions;
import java.util.*;
import org.bson.Document;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "donatrack.read-model.messaging.enabled", havingValue = "true")
public class ConsumidorEventos {
  private final MongoTemplate mongo;
  private final ObjectMapper mapper;
  private final ProjectionService projections;

  public ConsumidorEventos(
      MongoTemplate mongo, ObjectMapper mapper, ProjectionService projections) {
    this.mongo = mongo;
    this.mapper = mapper;
    this.projections = projections;
  }

  @RabbitListener(queues = "${donatrack.read-model.queue:donatrack.readmodel.v1.events}")
  public synchronized void consumir(Map<String, Object> raw) {
    EventoModeloLectura e = mapper.convertValue(raw, EventoModeloLectura.class);
    if (e.eventId() == null
        || e.source() == null
        || e.source().isBlank()
        || e.resourceType() == null
        || e.operation() == null
        || e.resourceId() == null
        || e.resourceId().isBlank()
        || e.payload() == null
        || e.occurredAt() == null
        || e.version() == null
        || e.version() < 1
        || !Integer.valueOf(1).equals(e.schemaVersion())
        || !Set.of("UPSERT", "DELETE").contains(e.operation()))
      throw new IllegalArgumentException("Evento incompleto o contrato no soportado");
    String collection = SnapshotStore.collection(e.resourceType());
    var events = mongo.getCollection("read_model_processed_events");
    if (events.find(new Document("_id", e.eventId())).first() != null) return;
    Document current =
        mongo.getCollection(collection).find(new Document("_id", e.resourceId())).first();
    if (current != null && current.containsKey("version") && !Objects.equals(current.getString("fuente"), e.source()))
      throw new IllegalArgumentException("Fuente del recurso inconsistente");
    Document data =
        e.operation().equals("DELETE") && current != null
            ? new Document(current)
            : new Document(e.payload());
    for (String key : List.of("_id", "version", "fuente", "eliminado", "ultimaActualizacion"))
      data.remove(key);
    data.put("version", e.version());
    data.put("fuente", e.source());
    data.put("eliminado", e.operation().equals("DELETE"));
    data.put("ultimaActualizacion", Date.from(e.occurredAt()));
    Document filter =
        new Document("_id", e.resourceId())
            .append(
                "$or",
                List.of(
                    new Document("version", new Document("$lt", e.version())),
                    new Document("version", new Document("$exists", false))));
    try {
      mongo
          .getCollection(collection)
          .replaceOne(
              filter,
              new Document(data).append("_id", e.resourceId()),
              new ReplaceOptions().upsert(true));
    } catch (com.mongodb.MongoWriteException ex) {
      if (ex.getError().getCode() != 11000) throw ex;
    }
    // Replay the CURRENT snapshots if an earlier attempt failed after the snapshot write.
    projections.refresh(e.resourceType(), e.resourceId(), current);
    events.replaceOne(
        new Document("_id", e.eventId()),
        new Document("_id", e.eventId()).append("occurredAt", Date.from(e.occurredAt())),
        new ReplaceOptions().upsert(true));
  }
}
