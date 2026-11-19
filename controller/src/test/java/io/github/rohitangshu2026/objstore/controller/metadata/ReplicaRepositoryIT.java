package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.uploading;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.worker;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Tests {@link ReplicaRepository} against a real SQLite file. */
class ReplicaRepositoryIT extends SqliteTestBase {

  @Autowired ObjectRepository objects;
  @Autowired WorkerRepository workers;
  @Autowired ReplicaRepository replicas;

  @Test
  void copiesOfAChunkReadBackSortedByWorker() {
    workers.insert(worker("worker1"));
    workers.insert(worker("worker2"));
    String id = objectId(1);
    objects.insert(uploading(id));
    ChunkRow chunk = chunk(id, 0);
    objects.insertChunk(chunk);
    ReplicaRow verified = new ReplicaRow(chunk.chunkId(), "worker2", ReplicaStatus.HEALTHY, NOW);
    ReplicaRow pending = new ReplicaRow(chunk.chunkId(), "worker1", ReplicaStatus.PENDING, null);

    replicas.insert(verified);
    replicas.insert(pending);

    assertThat(replicas.replicasOf(chunk.chunkId())).containsExactly(pending, verified);
  }

  @Test
  void chunkWithoutCopiesHasNone() {
    assertThat(replicas.replicasOf(chunk(objectId(2), 0).chunkId())).isEmpty();
  }
}
