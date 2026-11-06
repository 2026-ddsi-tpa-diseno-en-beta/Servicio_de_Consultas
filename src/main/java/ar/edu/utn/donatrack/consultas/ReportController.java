package ar.edu.utn.donatrack.consultas;

import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReportController {
  private final MongoTemplate mongo;
  private final ProjectionService projections;
  private final String adminKey;

  public ReportController(
      MongoTemplate mongo,
      ProjectionService projections,
      @org.springframework.beans.factory.annotation.Value("${READ_MODEL_ADMIN_KEY:}")
          String adminKey) {
    this.mongo = mongo;
    this.projections = projections;
    this.adminKey = adminKey;
  }

  @GetMapping("/donadores/{id}/ficha")
  public Document donor(@PathVariable String id) {
    Document d = mongo.findById(id, Document.class, "q_donadores");
    if (d == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Donador todavía no sincronizado o inexistente");
    return d;
  }

  @GetMapping("/necesidades/urgentes")
  public List<Document> needs(
      @RequestParam(required = false) Integer urgenciaMinima,
      @RequestParam(required = false) String tipo,
      @RequestParam(required = false) String categoriaID,
      @RequestParam(required = false) String subcategoriaID,
      @RequestParam(defaultValue = "100") int limite) {
    if (urgenciaMinima != null && (urgenciaMinima < 1 || urgenciaMinima > 10))
      throw new IllegalArgumentException("Urgencia entre 1 y 10");
    Criteria c = Criteria.where("cantidadFaltante").gt(0);
    if (urgenciaMinima != null) c.and("nivelDeUrgencia").gte(urgenciaMinima);
    if (tipo != null) {
      if (!Set.of("RECURRENTE", "EXTRAORDINARIA").contains(tipo))
        throw new IllegalArgumentException("Tipo de necesidad inválido");
      c.and("tipo").is(tipo);
    }
    if (categoriaID != null) c.and("categoriaID").is(categoriaID);
    if (subcategoriaID != null) c.and("subcategoriaID").is(subcategoriaID);
    return mongo.find(
        Query.query(c)
            .with(Sort.by(Sort.Order.desc("nivelDeUrgencia"), Sort.Order.asc("_id")))
            .limit(limit(limite)),
        Document.class,
        "q_necesidades");
  }

  @GetMapping("/depositos/reporte")
  public List<Document> depots(
      @RequestParam(required = false) String depositoID,
      @RequestParam(required = false) String desde,
      @RequestParam(required = false) String hasta) {
    LocalDateTime start = desde == null ? LocalDateTime.MIN : date(desde, false);
    LocalDateTime end = hasta == null ? LocalDateTime.MAX : date(hasta, true);
    if (start.isAfter(end)) throw new IllegalArgumentException("Desde debe ser anterior a hasta");
    Query q = depositoID == null ? new Query() : Query.query(Criteria.where("_id").is(depositoID));
    List<Document> docs = mongo.find(q.with(Sort.by("_id")), Document.class, "q_depositos");
    for (Document doc : docs) {
      // Each donation is counted once per depot, at its final recorded delivery.
      Map<String, Document> delivered = new HashMap<>();
      for (Document d : ProjectionService.list(doc, "entregas")) {
        LocalDateTime ingress = date(d.getString("fechaIngreso"), false),
            delivery = date(d.getString("fechaEntrega"), false);
        if (ingress.isBefore(start) || ingress.isAfter(end)) continue;
        String id = d.getString("donacionID");
        Document previous = delivered.get(id);
        if (previous == null || date(previous.getString("fechaEntrega"), false).isBefore(delivery))
          delivered.put(id, d);
      }
      var stats =
          delivered.values().stream()
              .mapToLong(
                  d ->
                      Duration.between(
                              date(d.getString("fechaIngreso"), false),
                              date(d.getString("fechaEntrega"), false))
                          .toMillis())
              .summaryStatistics();
      doc.put(
          "tiempoPromedioEntregaSegundos",
          stats.getCount() == 0 ? null : stats.getAverage() / 1000.0);
      doc.put("donacionesEntregadasEnRango", stats.getCount());
      doc.remove("entregas");
    }
    return docs;
  }

  @GetMapping("/calidad")
  public Document quality(@RequestParam(defaultValue = "10") int limiteDonadores) {
    List<Document> pipeline =
        List.of(
            new Document(
                "$group",
                new Document(
                        "_id",
                        new Document("categoriaID", "$categoriaID")
                            .append("subcategoriaID", "$subcategoriaID"))
                    .append("categoria", new Document("$first", "$categoria"))
                    .append("subcategoria", new Document("$first", "$subcategoria"))
                    .append("totalDonaciones", new Document("$sum", 1))
                    .append(
                        "conQueja",
                        new Document(
                            "$sum",
                            new Document(
                                "$cond",
                                List.of(
                                    new Document("$eq", List.of("$estado", "CONQUEJA")), 1, 0))))),
            new Document(
                "$addFields",
                new Document(
                    "proporcionQuejas",
                    new Document("$divide", List.of("$conQueja", "$totalDonaciones")))),
            new Document(
                "$sort",
                new Document("proporcionQuejas", -1)
                    .append("_id.categoriaID", 1)
                    .append("_id.subcategoriaID", 1)));
    List<Document> categories =
        mongo.getCollection("q_calidad").aggregate(pipeline).into(new ArrayList<>());
    List<Document> donors =
        mongo.find(
            Query.query(Criteria.where("cantidadQuejas").gt(0))
                .with(Sort.by(Sort.Order.desc("cantidadQuejas"), Sort.Order.asc("_id")))
                .limit(limit(limiteDonadores)),
            Document.class,
            "q_donadores");
    List<Document> ranking =
        donors.stream()
            .map(
                d ->
                    new Document("donadorID", d.get("donadorID"))
                        .append("nombre", d.get("nombre"))
                        .append("apellido", d.get("apellido"))
                        .append("estado", d.get("estado"))
                        .append("categoria", d.get("categoria"))
                        .append("cantidadQuejas", d.get("cantidadQuejas")))
            .toList();
    return new Document("porCategoria", categories)
        .append("donadoresConMasQuejas", ranking)
        .append("criterio", "Donaciones CONQUEJA / total de donaciones; no unidades");
  }

  @PostMapping("/admin/reconstruir")
  public Map<String, Object> rebuild(
      @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
    if (adminKey.isBlank()
        || !java.security.MessageDigest.isEqual(
            adminKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Clave de sincronización requerida");
    return projections.rebuild();
  }

  static int limit(int n) {
    if (n < 1 || n > 500) throw new IllegalArgumentException("Límite entre 1 y 500");
    return n;
  }

  static LocalDateTime date(String s, boolean end) {
    try {
      if (s.length() == 10)
        return end ? LocalDate.parse(s).atTime(LocalTime.MAX) : LocalDate.parse(s).atStartOfDay();
      try {
        return OffsetDateTime.parse(s)
            .atZoneSameInstant(ZoneId.of("America/Argentina/Buenos_Aires"))
            .toLocalDateTime();
      } catch (java.time.format.DateTimeParseException ex) {
        return LocalDateTime.parse(s);
      }
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Fecha ISO requerida: yyyy-MM-dd o fecha y hora", ex);
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalid(IllegalArgumentException ex) {
    return Map.of("detalle", ex.getMessage());
  }
}
