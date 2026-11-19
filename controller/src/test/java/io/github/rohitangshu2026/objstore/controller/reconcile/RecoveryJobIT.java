package io.github.rohitangshu2026.objstore.controller.reconcile;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.committed;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.uploading;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.controller.metadata.ChunkRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.SqliteTestBase;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.object.InFlightObjects;
import io.github.rohitangshu2026.objstore.controller.object.ObjectRemoval;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recovery after a crash at each step of an upload ({@code docs/architecture.md} §10.4): the
 * database and the fake workers are seeded with the state right after that step, then recovery runs
 * once.
 */
class RecoveryJobIT extends SqliteTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ObjectRepository objects;
  @Autowired ReplicaRepository replicas;
  @Autowired TombstoneRepository tombstones;
  @Autowired WorkerRepository workers;
  @Autowired TransactionTemplate transactions;

  private final FakeWorkerClient fake = new FakeWorkerClient();
  private final InFlightObjects inFlight = new InFlightObjects();
  private RecoveryJob recovery;

  @BeforeEach
  void setUp() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    workers.insert(worker("worker1", WorkerState.ALIVE));
    workers.insert(worker("worker2", WorkerState.ALIVE));
    workers.insert(worker("worker3", WorkerState.DEAD));
    MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
    ObjectRemoval removal =
        new ObjectRemoval(objects, replicas, tombstones, workers, fake, transactions, clock);
    recovery = new RecoveryJob(objects, removal, inFlight);
  }

  @Test
  void crashAfterTheObjectRowIsRemovedByRecovery() {
    objects.insert(uploading(objectId(1)));

    recovery.runOnce();

    assertThat(objects.find(objectId(1))).isEmpty();
  }

  @Test
  void crashAfterPendingRowsAndWrittenCopiesDeletesTheCopies() {
    String id = objectId(2);
    ChunkRow chunk = seedChunk(id, ReplicaStatus.PENDING, ReplicaStatus.PENDING);
    // U5: both copies landed, but the crash came before they were marked HEALTHY.
    fake.putChunk(ref("worker1"), chunk.chunkId(), new byte[] {1}, 1, "x");
    fake.putChunk(ref("worker2"), chunk.chunkId(), new byte[] {1}, 1, "x");

    recovery.runOnce();

    assertThat(objects.find(id)).isEmpty();
    assertThat(fake.chunksOn("worker1")).isEmpty();
    assertThat(fake.chunksOn("worker2")).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM tombstones").query(Integer.class).single()).isZero();
  }

  @Test
  void copiesOnAnUnreachableWorkerKeepTheirTombstones() {
    String id = objectId(3);
    // U6: one copy HEALTHY on worker1, its replacement still PENDING on the DEAD worker3.
    ChunkRow chunk = seedChunk(id, ReplicaStatus.HEALTHY, null);
    replicas.insert(new ReplicaRow(chunk.chunkId(), "worker3", ReplicaStatus.PENDING, null));

    recovery.runOnce();

    assertThat(objects.find(id)).isEmpty();
    assertThat(tombstones.forObject(id))
        .extracting(TombstoneRow::workerId, TombstoneRow::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("worker3", TombstoneReason.UPLOAD_ABORTED));
  }

  @Test
  void uploadStillInFlightIsNeverAborted() {
    String id = objectId(4);
    seedChunk(id, ReplicaStatus.HEALTHY, ReplicaStatus.PENDING);
    inFlight.add(id);

    recovery.runOnce();

    assertThat(objects.find(id)).map(ObjectRow::status).contains(ObjectStatus.UPLOADING);
    assertThat(replicas.replicasOf(id + "-000000")).hasSize(2);
  }

  @Test
  void unfinishedDeleteIsFinishedAndCommittedObjectsAreLeftAlone() {
    String deleting = objectId(5);
    objects.insert(uploading(deleting));
    jdbc.sql("UPDATE objects SET status = 'DELETING' WHERE object_id = ?").param(deleting).update();
    String kept = objectId(6);
    objects.insert(committed(kept, 1));

    recovery.runOnce();

    assertThat(objects.find(deleting)).isEmpty();
    assertThat(objects.find(kept)).isPresent();
  }

  /** Seeds an UPLOADING object with one chunk and copies on worker1 and worker2. */
  private ChunkRow seedChunk(String id, ReplicaStatus onWorker1, ReplicaStatus onWorker2) {
    ChunkRow chunk = chunk(id, 0);
    objects.insert(uploading(id));
    objects.insertChunk(chunk);
    if (onWorker1 != null) {
      replicas.insert(new ReplicaRow(chunk.chunkId(), "worker1", onWorker1, null));
    }
    if (onWorker2 != null) {
      replicas.insert(new ReplicaRow(chunk.chunkId(), "worker2", onWorker2, null));
    }
    return chunk;
  }

  private static WorkerRow worker(String id, WorkerState state) {
    return new WorkerRow(id, "http://" + id + ":9000", state, NOW, NOW);
  }

  private static WorkerRef ref(String id) {
    return new WorkerRef(id, "http://" + id + ":9000");
  }
}
