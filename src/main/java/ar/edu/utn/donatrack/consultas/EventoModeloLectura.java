package ar.edu.utn.donatrack.consultas;

import java.time.Instant;
import java.util.Map;

public record EventoModeloLectura(
    String eventId,
    String source,
    String resourceType,
    String resourceId,
    String operation,
    Instant occurredAt,
    Map<String, Object> payload
) {}
