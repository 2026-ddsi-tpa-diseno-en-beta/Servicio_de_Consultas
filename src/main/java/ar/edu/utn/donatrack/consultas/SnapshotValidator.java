package ar.edu.utn.donatrack.consultas;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Checks projection input shape, without deciding domain states or business rules. */
final class SnapshotValidator {
  private SnapshotValidator() {}

  static void validate(String type, Map<String, Object> data) {
    if (type.equals("donacion") || type.equals("asignacion")) {
      if (!(data.get("estado") instanceof String state) || state.isBlank())
        throw new IllegalArgumentException("Snapshot de " + type + ": estado requerido");
    }
    if (type.equals("donacion") && data.get("fechaIngreso") != null)
      date(data.get("fechaIngreso"), "donacion.fechaIngreso");
    if (type.equals("asignacion") && data.get("historial") != null) {
      if (!(data.get("historial") instanceof List<?> history))
        throw new IllegalArgumentException("Snapshot de asignacion: historial debe ser una lista");
      for (Object value : history) {
        if (!(value instanceof Map<?, ?> entry))
          throw new IllegalArgumentException("Snapshot de asignacion: cambio de historial inválido");
        if ("COMPLETADA".equals(entry.get("estado")))
          date(entry.get("fecha"), "asignacion.historial.fecha");
      }
    }
  }

  private static void date(Object value, String field) {
    if (!(value instanceof String text))
      throw new IllegalArgumentException("Snapshot: " + field + " requiere una fecha ISO");
    try {
      if (text.length() == 10) {
        LocalDate.parse(text);
        return;
      }
      try {
        OffsetDateTime.parse(text);
      } catch (DateTimeParseException withoutOffset) {
        LocalDateTime.parse(text);
      }
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("Snapshot: " + field + " requiere una fecha ISO");
    }
  }
}
