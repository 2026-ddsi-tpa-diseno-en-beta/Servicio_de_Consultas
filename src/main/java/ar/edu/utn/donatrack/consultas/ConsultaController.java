package ar.edu.utn.donatrack.consultas;

import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/")
public class ConsultaController {
  private final MongoTemplate mongo;

  public ConsultaController(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @GetMapping("/estado")
  public Map<String, Object> estado() {
    return Map.of(
        "servicio", "donatrack-servicio-consultas",
        "baseLectura", mongo.getDb().getName(),
        "colecciones",
            mongo.getCollectionNames().stream().filter(n -> n.startsWith("q_")).sorted().toList(),
        "fichasDonadores", mongo.getCollection("q_donadores").countDocuments(),
        "necesidades", mongo.getCollection("q_necesidades").countDocuments(),
        "depositos", mongo.getCollection("q_depositos").countDocuments(),
        "eventosProcesados", mongo.getCollection("read_model_processed_events").countDocuments(),
        "consistencia", "EVENTUAL: las consultas reflejan los eventos ya procesados");
  }

  @GetMapping("/recursos/{tipo}")
  public List<Document> listar(
      @PathVariable String tipo, @RequestParam(defaultValue = "100") int limite) {
    String collection = collection(tipo);
    int safeLimit = Math.max(1, Math.min(limite, 500));
    Query query = Query.query(Criteria.where("eliminado").ne(true)).limit(safeLimit);
    query.with(
        org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "ultimaActualizacion"));
    return mongo.find(query, Document.class, collection);
  }

  @GetMapping("/recursos/{tipo}/{id}")
  public ResponseEntity<Document> buscar(@PathVariable String tipo, @PathVariable String id) {
    Document doc = mongo.findById(id, Document.class, collection(tipo));
    return doc == null || Boolean.TRUE.equals(doc.get("eliminado"))
        ? ResponseEntity.notFound().build()
        : ResponseEntity.ok(doc);
  }

  @GetMapping("/estadisticas/{tipo}")
  public List<Document> agruparPorTipo(@PathVariable String tipo) {
    String collection = collection(tipo);
    Aggregation pipeline =
        Aggregation.newAggregation(
            Aggregation.group("estado").count().as("cantidad"),
            Aggregation.project("cantidad").and("_id").as("estado"));
    AggregationResults<Document> results = mongo.aggregate(pipeline, collection, Document.class);
    return results.getMappedResults();
  }

  private String collection(String tipo) {
    return SnapshotStore.collection(tipo);
  }
}
