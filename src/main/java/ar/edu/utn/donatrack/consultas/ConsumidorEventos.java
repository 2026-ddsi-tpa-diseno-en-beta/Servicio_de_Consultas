package ar.edu.utn.donatrack.consultas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.bson.Document;

import java.time.Instant;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "donatrack.read-model.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class ConsumidorEventos {
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;

    public ConsumidorEventos(MongoTemplate mongo, ObjectMapper mapper) {
        this.mongo = mongo;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "${donatrack.read-model.queue:donatrack.readmodel.events}")
    public void consumir(Map<String, Object> raw) {
        EventoModeloLectura evento = mapper.convertValue(raw, EventoModeloLectura.class);
        if (evento.eventId() == null || evento.resourceType() == null
            || evento.resourceId() == null || evento.payload() == null) {
            throw new IllegalArgumentException("Evento de modelo de lectura incompleto");
        }

        Query eventQuery = Query.query(Criteria.where("_id").is(evento.eventId()));
        if (mongo.exists(eventQuery, "read_model_processed_events")) {
            return;
        }

        String collection = safeCollection(evento.resourceType());
        Instant when = evento.occurredAt() == null ? Instant.EPOCH : evento.occurredAt();
        Query projectionQuery = Query.query(Criteria.where("_id").is(evento.resourceId()));
        Document existing = mongo.findOne(projectionQuery, Document.class, collection);
        Instant existingAt = Instant.EPOCH;
        if (existing != null && existing.get("ultimaActualizacion") != null) {
            Object value = existing.get("ultimaActualizacion");
            if (value instanceof java.util.Date date) existingAt = date.toInstant();
            else try { existingAt = Instant.parse(value.toString()); } catch (RuntimeException ignored) {}
        }
        if ("DELETE".equalsIgnoreCase(evento.operation())) {
            mongo.remove(projectionQuery, collection);
        } else if (existing == null || !existingAt.isAfter(when)) {
            Map<String, Object> snapshot = evento.payload();
            Update update = new Update().set("ultimaActualizacion", java.util.Date.from(when));
            snapshot.forEach((key, value) -> {
                if (!"_id".equals(key) && !"ultimaActualizacion".equals(key)) update.set(key, value);
            });
            update.set("tipoRecurso", evento.resourceType()).set("fuente", evento.source());
            mongo.upsert(projectionQuery, update, collection);
        }

        // Marcar como procesado al final: si falla la proyección, Rabbit puede reintentar.
        Document eventDoc = new Document("_id", evento.eventId())
            .append("source", evento.source())
            .append("resourceType", evento.resourceType())
            .append("resourceId", evento.resourceId())
            .append("operation", evento.operation())
            .append("occurredAt", java.util.Date.from(when));
        mongo.getCollection("read_model_processed_events").insertOne(eventDoc);
    }

    private String safeCollection(String resourceType) {
        if (!resourceType.matches("[a-zA-Z0-9_-]{1,50}")) {
            throw new IllegalArgumentException("resourceType inválido");
        }
        return "rm_" + resourceType.toLowerCase().replace('-', '_');
    }
}
