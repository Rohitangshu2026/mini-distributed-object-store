package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.worker;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Tests {@link TombstoneRepository} against a real SQLite file. */
class TombstoneRepositoryIT extends SqliteTestBase {

  @Autowired WorkerRepository workers;
  @Autowired TombstoneRepository tombstones;

  @Test
  void tombstonesOfAWorkerReadBackSortedByChunk() {
    workers.insert(worker("worker1"));
    workers.insert(worker("worker2"));
    // These chunks have no rows at all: a tombstone outlives its chunk.
    TombstoneRow second =
        new TombstoneRow(chunk(objectId(1), 1).chunkId(), "worker1", TombstoneReason.ORPHAN, NOW);
    TombstoneRow first =
        new TombstoneRow(
            chunk(objectId(1), 0).chunkId(), "worker1", TombstoneReason.OBJECT_DELETED, NOW);
    TombstoneRow elsewhere =
        new TombstoneRow(
            chunk(objectId(1), 0).chunkId(), "worker2", TombstoneReason.WRITE_FAILED, NOW);

    tombstones.insert(second);
    tombstones.insert(first);
    tombstones.insert(elsewhere);

    assertThat(tombstones.forWorker("worker1")).containsExactly(first, second);
  }
}
