package ar.edu.utn.donatrack.consultas;

import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/** Rebuild only affected reports from local Mongo snapshots. No remote module calls. */
@Service
public class ProjectionService {
  private final SnapshotStore store;
  private final MongoTemplate mongo;

  public ProjectionService(SnapshotStore store, MongoTemplate mongo) {
    this.store = store;
    this.mongo = mongo;
  }

  static String str(Document d, String key) {
    Object v = d.get(key);
    return v == null ? null : v.toString();
  }

  static long number(Document d, String key) {
    Object v = d.get(key);
    return v instanceof Number n ? n.longValue() : 0;
  }

  @SuppressWarnings("unchecked")
  static List<Document> list(Document d, String key) {
    Object value = d.get(key);
    if (!(value instanceof List<?> values)) return List.of();
    return values.stream()
        .filter(v -> v instanceof Map)
        .map(v -> new Document((Map<String, Object>) v))
        .toList();
  }

  private static Set<String> ids(List<Document> docs) {
    var result = new HashSet<String>();
    docs.forEach(
        d -> {
          if (d.get("_id") != null) result.add(str(d, "_id"));
        });
    return result;
  }

  private void save(String collection, String id, Document doc) {
    doc.put("_id", id);
    doc.put("actualizadoEn", new Date());
    mongo.save(doc, collection);
  }

  private void remove(String collection, String id) {
    mongo.remove(Query.query(Criteria.where("_id").is(id)), collection);
  }

  public synchronized void refresh(String type, String id, Document before) {
    Set<String> donors = new HashSet<>(),
        needs = new HashSet<>(),
        depots = new HashSet<>(),
        products = new HashSet<>();
    Document now = store.get(type, id);
    for (Document row : Arrays.asList(now, before == null ? new Document() : before)) {
      switch (type) {
        case "donador", "incentivo_donador" -> donors.add(id);
        case "donacion" -> {
          add(donors, str(row, "donadorID"));
          add(depots, str(row, "depositoID"));
          projectQuality(id);
        }
        case "necesidad" -> needs.add(id);
        case "entidad" -> needs.addAll(ids(store.relatedHistorical("necesidad", "entidadID", id)));
        case "deposito" -> {
          depots.add(id);
          list(row, "stockActual").forEach(p -> add(products, str(p, "productoID")));
          store
              .related("paquete", "depositoID", id)
              .forEach(p -> add(products, str(p, "productoID")));
        }
        case "paquete" -> {
          add(depots, str(row, "depositoID"));
          add(products, str(row, "productoID"));
        }
        case "asignacion" -> {
          add(needs, str(row, "necesidadID"));
          add(depots, str(row, "depositoID"));
          add(donors, str(store.get("donacion", row.get("donacionID")), "donadorID"));
        }
        case "producto" -> products.add(id);
        case "categoria" -> {
          products.addAll(ids(store.related("producto", "categoriaId", id)));
          products.addAll(ids(store.related("producto", "subcategoriaId", id)));
        }
        case "mision", "insignia" -> donors.addAll(ids(store.all("incentivo_donador")));
        case "queja" -> add(donors, str(row, "donadorID"));
        default -> {}
      }
    }
    for (String product : products) {
      needs.addAll(ids(store.related("necesidad", "productoSolicitadoID", product)));
      for (Document donation : store.related("donacion", "productoID", product)) {
        add(donors, str(donation, "donadorID"));
        projectQuality(str(donation, "_id"));
      }
    }
    for (String need : needs) {
      projectNeed(need);
      for (Document a : store.related("asignacion", "necesidadID", need)) {
        add(depots, str(a, "depositoID"));
        add(donors, str(store.get("donacion", a.get("donacionID")), "donadorID"));
      }
    }
    donors.forEach(this::projectDonor);
    depots.forEach(this::projectDepot);
  }

  private static void add(Set<String> values, String value) {
    if (value != null) values.add(value);
  }

  public synchronized Map<String, Object> rebuild() {
    for (String collection : List.of("q_donadores", "q_necesidades", "q_depositos", "q_calidad"))
      mongo.getCollection(collection).deleteMany(new Document());
    store.all("donacion").forEach(d -> projectQuality(str(d, "_id")));
    store.all("donador").forEach(d -> projectDonor(str(d, "_id")));
    store.all("necesidad").forEach(d -> projectNeed(str(d, "_id")));
    store.all("deposito").forEach(d -> projectDepot(str(d, "_id")));
    return Map.of(
        "estado",
        "RECONSTRUIDO",
        "colecciones",
        List.of("q_donadores", "q_necesidades", "q_depositos", "q_calidad"));
  }

  private Document product(Object id) {
    Document p = store.get("producto", id);
    return new Document("productoID", id)
        .append("nombreProducto", p.get("nombre"))
        .append("categoriaID", p.get("categoriaId"))
        .append("categoria", store.get("categoria", p.get("categoriaId")).get("nombre"))
        .append("subcategoriaID", p.get("subcategoriaId"))
        .append("subcategoria", store.get("categoria", p.get("subcategoriaId")).get("nombre"));
  }

  private void projectQuality(String id) {
    Document donation = store.get("donacion", id);
    if (donation.isEmpty()) {
      remove("q_calidad", id);
      return;
    }
    Document result = product(donation.get("productoID"));
    result.putAll(donation);
    result.remove("_id");
    save("q_calidad", id, result);
  }

  private void projectDonor(String id) {
    Document donor = store.get("donador", id);
    if (donor.isEmpty()) {
      remove("q_donadores", id);
      return;
    }
    Document result = new Document(donor);
    result.remove("_id");
    result.put("donadorID", id);
    Document incentive = store.get("incentivo_donador", id);
    result.put(
        "insignias",
        list(incentive, "insignias").stream().map(i -> catalog("insignia", i)).toList());
    result.put(
        "misiones", list(incentive, "misiones").stream().map(m -> catalog("mision", m)).toList());
    result.put("progresos", list(incentive, "progresos"));
    Object active = incentive.get("misionEnCurso");
    result.put(
        "misionEnCurso",
        active instanceof Map<?, ?>
            ? catalog("mision", new Document((Map<String, Object>) active))
            : null);
    List<Document> donations = store.related("donacion", "donadorID", id);
    Map<String, Long> states = new TreeMap<>();
    donations.forEach(d -> states.merge(str(d, "estado"), 1L, Long::sum));
    result.put("donacionesPorEstado", states);
    result.put("cantidadDonaciones", donations.size());
    Map<String, Long> entities = new TreeMap<>();
    for (Document a : store.relatedIds("asignacion", "donacionID", ids(donations))) {
      if (!"COMPLETADA".equals(str(a, "estado"))) continue;
      String entity = str(store.historical("necesidad", a.get("necesidadID")), "entidadID");
      if (entity != null) entities.merge(entity, number(a, "cantidadAsignada"), Long::sum);
    }
    result.put(
        "entidadesBeneficiadas",
        entities.entrySet().stream()
            .map(
                e ->
                    new Document("entidadID", e.getKey())
                        .append(
                            "razonSocial",
                            store.historical("entidad", e.getKey()).get("razonSocial"))
                        .append("unidades", e.getValue()))
            .toList());
    save("q_donadores", id, result);
  }

  private void projectNeed(String id) {
    Document need = store.get("necesidad", id);
    if (need.isEmpty()) {
      remove("q_necesidades", id);
      return;
    }
    Document result = product(need.get("productoSolicitadoID"));
    result.putAll(need);
    result.remove("_id");
    result.put("necesidadID", id);
    result.put("entidad", store.get("entidad", need.get("entidadID")));
    result.put("cantidadRecibida", number(need, "cantidadAsignada"));
    result.put(
        "cantidadFaltante",
        Math.max(0, number(need, "cantidadObjetivo") - number(need, "cantidadAsignada")));
    Map<String, Long> stock = new TreeMap<>();
    for (Document p : store.related("paquete", "productoID", need.get("productoSolicitadoID")))
      if ("EN_STOCK".equals(str(p, "estado")))
        stock.merge(str(p, "depositoID"), number(p, "cantidad"), Long::sum);
    result.put(
        "stockPorDeposito",
        stock.entrySet().stream()
            .map(
                e ->
                    new Document("depositoID", e.getKey())
                        .append("nombre", store.get("deposito", e.getKey()).get("nombre"))
                        .append("cantidadDisponible", e.getValue()))
            .toList());
    List<Document> pending =
        store.related("asignacion", "necesidadID", id).stream()
            .filter(a -> "ASIGNADA".equals(str(a, "estado")))
            .toList();
    result.put(
        "unidadesAsignadasSinEntregar",
        pending.stream().mapToLong(a -> number(a, "cantidadAsignada")).sum());
    result.put("asignacionesPendientes", pending);
    save("q_necesidades", id, result);
  }

  private Document catalog(String type, Document embedded) {
    Document result = new Document(embedded), latest = store.get(type, embedded.get("id"));
    for (String field :
        List.of("nombre", "descripcion", "tipo", "categoriaInicio", "categoriaFin", "insigniaID"))
      if (latest.containsKey(field)) result.put(field, latest.get(field));
    return result;
  }

  private void projectDepot(String id) {
    Document depot = store.get("deposito", id);
    if (depot.isEmpty()) {
      remove("q_depositos", id);
      return;
    }
    Document result = new Document(depot);
    result.remove("_id");
    result.put("depositoID", id);
    long capacity = number(depot, "capacidadMaxima");
    result.put(
        "ocupacion", capacity == 0 ? null : (double) number(depot, "ocupacionActual") / capacity);
    Map<String, Long> states = new TreeMap<>();
    Set<String> entities = new HashSet<>();
    List<Document> deliveries = new ArrayList<>();
    for (Document a : store.related("asignacion", "depositoID", id)) {
      states.merge(str(a, "estado"), 1L, Long::sum);
      if (!"COMPLETADA".equals(str(a, "estado"))) continue;
      Document need = store.historical("necesidad", a.get("necesidadID"));
      add(entities, str(need, "entidadID"));
      String end =
          list(a, "historial").stream()
              .filter(h -> "COMPLETADA".equals(str(h, "estado")))
              .map(h -> str(h, "fecha"))
              .findFirst()
              .orElse(null);
      Document donation = store.get("donacion", a.get("donacionID"));
      if (end != null && donation.get("fechaIngreso") != null)
        deliveries.add(
            new Document("donacionID", a.get("donacionID"))
                .append("fechaIngreso", donation.get("fechaIngreso"))
                .append("fechaEntrega", end));
    }
    result.put("asignacionesPorEstado", states);
    result.put("entidadesAtendidas", entities.stream().sorted().toList());
    result.put("cantidadEntidadesAtendidas", entities.size());
    result.put("entregas", deliveries);
    save("q_depositos", id, result);
  }
}
