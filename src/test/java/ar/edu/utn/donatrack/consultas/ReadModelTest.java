package ar.edu.utn.donatrack.consultas;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Executes Mongo wire operations against an isolated in-memory Mongo-compatible server. */
class ReadModelTest {
  @Test
  void entityRenameRefreshesDeliveredHistoryAfterNeedDeletion() {
    fixture();
    consumer.consumir(event("necesidad", "n", ++sequence, "DELETE", Map.of()));
    up("entidad", "e", Map.of("razonSocial", "Escuela Renombrada"));
    assertEquals(
        "Escuela Renombrada",
        ProjectionService.list(reports.donor("d"), "entidadesBeneficiadas")
            .getFirst()
            .get("razonSocial"));
    assertEquals(
        10L,
        ProjectionService.list(reports.donor("d"), "entidadesBeneficiadas")
            .getFirst()
            .get("unidades"));
  }

  @Test
  void pendingReservationsRemainDeliverableWhenTheRecurringPeriodChanges() {
    fixture();
    up(
        "necesidad",
        "n",
        Map.of(
            "entidadID",
            "e",
            "productoSolicitadoID",
            "p",
            "cantidadObjetivo",
            20,
            "cantidadAsignada",
            0,
            "nivelDeUrgencia",
            9,
            "tipo",
            "RECURRENTE",
            "inicioPeriodo",
            "2026-11-02"));
    up(
        "asignacion",
        "b",
        Map.of(
            "depositoID",
            "z",
            "productoID",
            "p",
            "donacionID",
            "y",
            "necesidadID",
            "n",
            "cantidadAsignada",
            2,
            "estado",
            "ASIGNADA",
            "fecha",
            "2026-10-26T10:00:00"));
    var report = reports.needs(1, "RECURRENTE", null, null, 100).getFirst();
    assertEquals(2L, report.get("unidadesAsignadasSinEntregar"));
    assertEquals(1, ProjectionService.list(report, "asignacionesPendientes").size());
    projections.rebuild();
    assertEquals(
        2L,
        reports
            .needs(1, "RECURRENTE", null, null, 100)
            .getFirst()
            .get("unidadesAsignadasSinEntregar"));
  }

  MongoServer server;
  com.mongodb.client.MongoClient client;
  MongoTemplate mongo;
  ConsumidorEventos consumer;
  ProjectionService projections;
  ReportController reports;
  long sequence;

  @BeforeEach
  void setup() {
    server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    client =
        MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort() + "/test");
    mongo = new MongoTemplate(client, "test");
    projections = new ProjectionService(new SnapshotStore(mongo), mongo);
    consumer =
        new ConsumidorEventos(mongo, new ObjectMapper().findAndRegisterModules(), projections);
    reports = new ReportController(mongo, projections, "test-key");
  }

  @AfterEach
  void close() {
    client.close();
    server.shutdownNow();
  }

  Map<String, Object> event(
      String type, String id, long version, String op, Map<String, Object> payload) {
    return Map.of(
        "eventId",
        type + id + version,
        "source",
        "module-" + type,
        "resourceType",
        type,
        "resourceId",
        id,
        "operation",
        op,
        "occurredAt",
        "2026-11-06T12:00:00Z",
        "payload",
        payload,
        "version",
        version,
        "schemaVersion",
        1);
  }

  void up(String type, String id, Map<String, Object> data) {
    consumer.consumir(event(type, id, ++sequence, "UPSERT", data));
  }

  void fixture() {
    up("categoria", "c", Map.of("nombre", "Alimentos"));
    up("categoria", "s", Map.of("nombre", "Secos", "categoriaPadreId", "c"));
    up("producto", "p", Map.of("nombre", "Arroz", "categoriaId", "c", "subcategoriaId", "s"));
    up(
        "donador",
        "d",
        Map.of(
            "nombre",
            "Ana",
            "apellido",
            "Demo",
            "estado",
            "VERIFICADO",
            "categoria",
            "OCASIONAL",
            "historialCategorias",
            List.of("OCASIONAL"),
            "cantidadQuejas",
            1,
            "quejas",
            List.of(Map.of("donacionID", "x"))));
    up("entidad", "e", Map.of("razonSocial", "Escuela"));
    up(
        "necesidad",
        "n",
        Map.of(
            "entidadID",
            "e",
            "productoSolicitadoID",
            "p",
            "cantidadObjetivo",
            20,
            "cantidadAsignada",
            10,
            "nivelDeUrgencia",
            9,
            "tipo",
            "EXTRAORDINARIA"));
    up(
        "deposito",
        "z",
        Map.of(
            "nombre",
            "Central",
            "capacidadMaxima",
            100,
            "ocupacionActual",
            5,
            "algoritmo",
            "SUB_ATENDIDOS"));
    up(
        "donacion",
        "x",
        Map.of(
            "donadorID",
            "d",
            "depositoID",
            "z",
            "productoID",
            "p",
            "cantidad",
            10,
            "estado",
            "CONQUEJA",
            "fechaIngreso",
            "2026-11-05T10:00:00"));
    up(
        "donacion",
        "y",
        Map.of(
            "donadorID",
            "d",
            "depositoID",
            "z",
            "productoID",
            "p",
            "cantidad",
            5,
            "estado",
            "INGRESADA",
            "fechaIngreso",
            "2026-11-06T10:00:00"));
    up(
        "paquete",
        "stock",
        Map.of(
            "depositoID",
            "z",
            "productoID",
            "p",
            "donacionID",
            "y",
            "cantidad",
            5,
            "estado",
            "EN_STOCK"));
    up(
        "asignacion",
        "a",
        Map.of(
            "depositoID",
            "z",
            "productoID",
            "p",
            "donacionID",
            "x",
            "necesidadID",
            "n",
            "cantidadAsignada",
            10,
            "estado",
            "COMPLETADA",
            "historial",
            List.of(Map.of("estado", "COMPLETADA", "fecha", "2026-11-05T11:00:00"))));
    up(
        "asignacion",
        "b",
        Map.of(
            "depositoID",
            "z",
            "productoID",
            "p",
            "donacionID",
            "y",
            "necesidadID",
            "n",
            "cantidadAsignada",
            2,
            "estado",
            "ASIGNADA"));
    up(
        "incentivo_donador",
        "d",
        Map.of(
            "insignias",
            List.of(Map.of("nombre", "Completitud")),
            "misionEnCurso",
            Map.of("nombre", "Exitosas", "progreso", 3, "objetivo", 20)));
  }

  @Test
  void allFourReportsCombineModulesAndHaveExactResults() {
    fixture();
    Document donor = reports.donor("d");
    assertEquals(2, donor.get("cantidadDonaciones"));
    assertEquals(
        10L, ProjectionService.list(donor, "entidadesBeneficiadas").getFirst().get("unidades"));
    assertEquals(3, new Document((Map) donor.get("misionEnCurso")).get("progreso"));
    var needs = reports.needs(8, "EXTRAORDINARIA", "c", "s", 100);
    assertEquals(1, needs.size());
    assertEquals(10L, needs.getFirst().get("cantidadFaltante"));
    assertEquals(2L, needs.getFirst().get("unidadesAsignadasSinEntregar"));
    assertEquals(
        5L,
        ProjectionService.list(needs.getFirst(), "stockPorDeposito")
            .getFirst()
            .get("cantidadDisponible"));
    Document depot = reports.depots("z", "2026-11-05", "2026-11-05").getFirst();
    assertEquals(0.05, ((Number) depot.get("ocupacion")).doubleValue(), 0.0001);
    assertEquals(
        3600.0, ((Number) depot.get("tiempoPromedioEntregaSegundos")).doubleValue(), 0.0001);
    assertEquals(1, depot.get("cantidadEntidadesAtendidas"));
    Document quality = reports.quality(10);
    assertEquals(
        0.5,
        ((Number)
                ProjectionService.list(quality, "porCategoria").getFirst().get("proporcionQuejas"))
            .doubleValue(),
        0.0001);
    assertEquals(
        "d", ProjectionService.list(quality, "donadoresConMasQuejas").getFirst().get("donadorID"));
  }

  @Test
  void repeatedMessagesDoNotDuplicateUnitsOrCategories() {
    fixture();
    var e =
        event(
            "asignacion",
            "a",
            100,
            "UPSERT",
            Map.of(
                "depositoID",
                "z",
                "productoID",
                "p",
                "donacionID",
                "x",
                "necesidadID",
                "n",
                "cantidadAsignada",
                10,
                "estado",
                "COMPLETADA"));
    consumer.consumir(e);
    consumer.consumir(e);
    assertEquals(
        10L,
        ProjectionService.list(reports.donor("d"), "entidadesBeneficiadas")
            .getFirst()
            .get("unidades"));
  }

  @Test
  void oldDeletesCannotRemoveNewSnapshotsAndOldUpsertsCannotResurrectDeletedOnes() {
    up("donador", "d", Map.of("nombre", "Ana", "estado", "VERIFICADO"));
    consumer.consumir(
        event("donador", "d", 100, "UPSERT", Map.of("nombre", "Nueva", "estado", "VERIFICADO")));
    consumer.consumir(event("donador", "d", 99, "DELETE", Map.of()));
    assertEquals("Nueva", reports.donor("d").get("nombre"));
    consumer.consumir(event("donador", "d", 101, "DELETE", Map.of()));
    consumer.consumir(event("donador", "d", 98, "UPSERT", Map.of("nombre", "Vieja")));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class, () -> reports.donor("d"));
  }

  @Test
  void filtersAndEmptyTimeRangesAreExplicit() {
    fixture();
    assertTrue(reports.needs(10, null, null, null, 10).isEmpty());
    assertNull(
        reports
            .depots("z", "2026-11-06", "2026-11-06")
            .getFirst()
            .get("tiempoPromedioEntregaSegundos"));
    assertThrows(IllegalArgumentException.class, () -> reports.needs(11, null, null, null, 100));
    assertThrows(
        IllegalArgumentException.class, () -> reports.depots(null, "2026-11-07", "2026-11-06"));
    assertThrows(IllegalArgumentException.class, () -> reports.quality(0));
  }

  @Test
  void rebuildAndProductChangesRefreshJoinedDocuments() {
    fixture();
    up(
        "producto",
        "p",
        Map.of("nombre", "Arroz integral", "categoriaId", "c", "subcategoriaId", "s"));
    assertEquals(
        "Arroz integral",
        reports.needs(null, null, null, null, 100).getFirst().get("nombreProducto"));
    mongo.dropCollection("q_donadores");
    projections.rebuild();
    assertEquals("Ana", reports.donor("d").get("nombre"));
  }

  @Test
  void projectionFailureIsRetriedEvenWhenSnapshotWasAlreadyWritten() {
    fixture();
    mongo.getCollection("read_model_processed_events").deleteMany(new Document());
    mongo.dropCollection("q_donadores");
    consumer.consumir(event("donador", "d", 1, "UPSERT", Map.of("nombre", "Old")));
    assertEquals("Ana", reports.donor("d").get("nombre"));
  }

  @Test
  void invalidContractsAndUnknownResourcesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> consumer.consumir(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.consumir(event("arbitrary", "x", 1, "UPSERT", Map.of())));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> reports.rebuild("wrong"));
  }

  @Test
  void legacySnapshotsCanMigrateInTheExistingDatabaseWithoutAllowingLaterSourceChanges() {
    mongo
        .getCollection("rm_donador")
        .insertOne(
            new Document("_id", "legacy")
                .append("fuente", "donatrack")
                .append("nombre", "Anterior"));
    consumer.consumir(
        event(
            "donador", "legacy", 1, "UPSERT", Map.of("nombre", "Actual", "estado", "VERIFICADO")));
    assertEquals("Actual", reports.donor("legacy").get("nombre"));
    Map<String, Object> conflicting =
        new HashMap<>(event("donador", "legacy", 2, "UPSERT", Map.of("nombre", "Otra fuente")));
    conflicting.put("source", "other-module");
    assertThrows(IllegalArgumentException.class, () -> consumer.consumir(conflicting));
    assertEquals("Actual", reports.donor("legacy").get("nombre"));
  }

  @Test
  void deletingNeedKeepsHistoricalDeliveredUnitsAndCatalogChangesRefreshNames() {
    fixture();
    up(
        "incentivo_donador",
        "d",
        Map.of(
            "insignias",
            List.of(Map.of("id", "b", "nombre", "Anterior")),
            "misionEnCurso",
            Map.of("id", "m", "nombre", "Anterior", "progreso", 3, "objetivo", 20)));
    up("insignia", "b", Map.of("nombre", "Premio actualizado"));
    up("mision", "m", Map.of("nombre", "Misión actualizada"));
    Document donor = reports.donor("d");
    assertEquals(
        "Premio actualizado", ProjectionService.list(donor, "insignias").getFirst().get("nombre"));
    assertEquals(
        "Misión actualizada", new Document((Map) donor.get("misionEnCurso")).get("nombre"));
    consumer.consumir(event("necesidad", "n", ++sequence, "DELETE", Map.of()));
    assertEquals(
        10L,
        ProjectionService.list(reports.donor("d"), "entidadesBeneficiadas")
            .getFirst()
            .get("unidades"));
    assertTrue(reports.needs(null, null, null, null, 100).isEmpty());
  }
}
