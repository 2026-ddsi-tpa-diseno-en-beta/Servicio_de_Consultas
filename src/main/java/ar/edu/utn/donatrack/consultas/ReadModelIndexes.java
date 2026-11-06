package ar.edu.utn.donatrack.consultas;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class ReadModelIndexes implements ApplicationRunner {
  private final MongoTemplate mongo;

  public ReadModelIndexes(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public void run(ApplicationArguments args) {
    for (String field : java.util.List.of("donadorID", "productoID", "depositoID"))
      index("rm_donacion", field);
    for (String field : java.util.List.of("necesidadID", "donacionID", "depositoID"))
      index("rm_asignacion", field);
    index("rm_paquete", "productoID");
    index("rm_necesidad", "entidadID");
    index("rm_necesidad", "productoSolicitadoID");
    index("rm_producto", "categoriaId");
    index("rm_producto", "subcategoriaId");
    index("q_donadores", "cantidadQuejas");
    index("q_necesidades", "nivelDeUrgencia");
  }

  private void index(String collection, String field) {
    mongo.indexOps(collection).ensureIndex(new Index().on(field, Direction.ASC));
  }
}
