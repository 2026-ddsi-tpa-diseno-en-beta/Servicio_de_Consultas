package ar.edu.utn.donatrack.consultas;

import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

@Component
public class SnapshotStore {
  public static final Set<String> TYPES =
      Set.of(
          "donador",
          "entidad",
          "necesidad",
          "queja",
          "donacion",
          "producto",
          "categoria",
          "deposito",
          "paquete",
          "asignacion",
          "incentivo_donador",
          "mision",
          "insignia");
  private final MongoTemplate mongo;

  public SnapshotStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public static String collection(String type) {
    if (!TYPES.contains(type)) throw new IllegalArgumentException("Tipo de recurso desconocido");
    return "rm_" + type;
  }

  public List<Document> all(String type) {
    return find(type, new Criteria());
  }

  public List<Document> find(String type, Criteria criteria) {
    return mongo.find(
        Query.query(new Criteria().andOperator(Criteria.where("eliminado").ne(true), criteria)),
        Document.class,
        collection(type));
  }

  public Document get(String type, Object id) {
    if (id == null) return new Document();
    Document d = mongo.findById(id.toString(), Document.class, collection(type));
    return d == null || Boolean.TRUE.equals(d.get("eliminado")) ? new Document() : d;
  }

  public Document historical(String type, Object id) {
    if (id == null) return new Document();
    Document doc = mongo.findById(id.toString(), Document.class, collection(type));
    return doc == null ? new Document() : doc;
  }

  public List<Document> related(String type, String field, Object value) {
    return find(type, Criteria.where(field).is(value));
  }

  /** Tombstones retain the relationships needed to refresh delivered donations. */
  public List<Document> relatedHistorical(String type, String field, Object value) {
    return mongo.find(
        Query.query(Criteria.where(field).is(value)), Document.class, collection(type));
  }

  public List<Document> relatedIds(String type, String field, Collection<?> ids) {
    return ids.isEmpty() ? List.of() : find(type, Criteria.where(field).in(ids));
  }
}
