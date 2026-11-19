package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.committed;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.startController;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.worker;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that a restart loses nothing: the application writes metadata, shuts down, starts again on
 * the same database file, and reads back the same schema and rows.
 */
class MetadataReopenIT {

  @Test
  void objectWithThreeChunksAndSixCopiesSurvivesReopening(@TempDir Path dir) {
    String url = "jdbc:sqlite:" + dir.resolve("controller.db");
    String id = objectId(1);
    ObjectRow object = committed(id, 3);
    List<WorkerRow> workerRows = List.of(worker("worker1"), worker("worker2"), worker("worker3"));
    List<ChunkRow> chunkRows = List.of(chunk(id, 0), chunk(id, 1), chunk(id, 2));
    // Each chunk on two different workers: six copies, never two on one worker (I4).
    List<ReplicaRow> replicaRows =
        List.of(
            healthy(chunkRows.get(0), "worker1"),
            healthy(chunkRows.get(0), "worker2"),
            healthy(chunkRows.get(1), "worker2"),
            healthy(chunkRows.get(1), "worker3"),
            healthy(chunkRows.get(2), "worker3"),
            healthy(chunkRows.get(2), "worker1"));

    try (ConfigurableApplicationContext app = startController(url)) {
      WorkerRepository workers = app.getBean(WorkerRepository.class);
      ObjectRepository objects = app.getBean(ObjectRepository.class);
      ReplicaRepository replicas = app.getBean(ReplicaRepository.class);
      app.getBean(TransactionTemplate.class)
          .executeWithoutResult(status -> {
            workerRows.forEach(workers::insert);
            objects.insert(object);
            chunkRows.forEach(objects::insertChunk);
            replicaRows.forEach(replicas::insert);
          });
    }

    try (ConfigurableApplicationContext app = startController(url)) {
      ObjectRepository objects = app.getBean(ObjectRepository.class);
      ReplicaRepository replicas = app.getBean(ReplicaRepository.class);

      assertThat(objects.find(id)).contains(object);
      assertThat(objects.chunksOf(id)).isEqualTo(chunkRows);
      assertThat(app.getBean(WorkerRepository.class).findAll()).isEqualTo(workerRows);
      assertThat(
              chunkRows.stream()
                  .flatMap(chunk -> replicas.replicasOf(chunk.chunkId()).stream())
                  .toList())
          .hasSize(6)
          .containsExactlyInAnyOrderElementsOf(replicaRows);
      // Flyway found the schema up to date and applied nothing again.
      assertThat(
              app.getBean(JdbcClient.class)
                  .sql("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL")
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
    }
  }

  private static ReplicaRow healthy(ChunkRow chunk, String workerId) {
    return new ReplicaRow(chunk.chunkId(), workerId, ReplicaStatus.HEALTHY, NOW);
  }
}
