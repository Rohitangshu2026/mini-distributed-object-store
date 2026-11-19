package io.github.rohitangshu2026.objstore.controller.reconcile;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.controller.config.ControllerProperties;
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
import io.github.rohitangshu2026.objstore.controller.reconcile.TombstoneJob.Result;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tombstone processing and reconciliation against a real SQLite file and in-memory workers: the
 * tombstone table of {@code docs/architecture.md} §8 (with the N5 rule) and the comparison table of
 * §9, including the startup gate and orphan cleanup.
 */
class RejoinIT extends SqliteTestBase {

  private static final Duration GC_GRACE = Duration.ofSeconds(20);
  private static final Duration STARTUP_GRACE = Duration.ofMinutes(2);

  @Autowired JdbcClient jdbc;
  @Autowired ObjectRepository objects;
  @Autowired ReplicaRepository replicas;
  @Autowired TombstoneRepository tombstones;
  @Autowired WorkerRepository workers;
  @Autowired TransactionTemplate transactions;

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
  private final FakeWorkerClient fake = new FakeWorkerClient();
  private ThreadPoolTaskScheduler scheduler;
  private TombstoneJob tombstoneJob;
  private Reconciler reconciler;

  @BeforeEach
  void setUp() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    for (int i = 1; i <= 4; i++) {
      workers.insert(
          new WorkerRow("worker" + i, "http://worker" + i + ":9000", WorkerState.ALIVE, NOW, NOW));
    }
    scheduler = new ManualScheduler();
    scheduler.initialize();
    tombstoneJob =
        new TombstoneJob(
            tombstones, replicas, objects, workers, fake, transactions, scheduler, clock);
    reconciler = reconciler(false);
    // Past the startup grace, so the gate is open unless a test says otherwise.
    clock.advance(STARTUP_GRACE);
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdown();
  }

  // Tombstones (§8)

  @Test
  void copyOfAGoneObjectIsDeletedAndItsTombstoneRemoved() {
    String chunk = objectId(1) + "-000000";
    fakeHolds("worker1", chunk, bytes(10));
    tombstones.insert(new TombstoneRow(chunk, "worker1", TombstoneReason.OBJECT_DELETED, NOW));

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.DELETED, 1));
    assertThat(fake.chunksOn("worker1")).doesNotContainKey(chunk);
    assertThat(tombstones.forWorker("worker1")).isEmpty();
  }

  @Test
  void tombstoneOfAnUploadingObjectWaits() {
    ChunkRow chunk = seed(ObjectStatus.UPLOADING, Map.of());
    tombstones.insert(
        new TombstoneRow(chunk.chunkId(), "worker1", TombstoneReason.WRITE_FAILED, NOW));

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.WAITING, 1));
    assertThat(tombstones.forWorker("worker1")).hasSize(1);
  }

  @Test
  void committedCopyIsDeletedOnlyWhenTwoHealthyCopiesRemainElsewhere() {
    ChunkRow chunk =
        seed(
            ObjectStatus.COMMITTED,
            Map.of("worker2", ReplicaStatus.HEALTHY, "worker3", ReplicaStatus.HEALTHY));
    fakeHolds("worker1", chunk.chunkId(), bytesOf(chunk));
    tombstones.insert(
        new TombstoneRow(chunk.chunkId(), "worker1", TombstoneReason.EXTRA_COPY, NOW));

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.DELETED, 1));
    assertThat(fake.chunksOn("worker1")).doesNotContainKey(chunk.chunkId());
  }

  @Test
  void neededCopyIsAdoptedNeverDeleted() {
    // Only one HEALTHY copy elsewhere: deleting worker1's copy would break N5.
    ChunkRow chunk = seed(ObjectStatus.COMMITTED, Map.of("worker2", ReplicaStatus.HEALTHY));
    fakeHolds("worker1", chunk.chunkId(), bytesOf(chunk));
    tombstones.insert(
        new TombstoneRow(chunk.chunkId(), "worker1", TombstoneReason.EXTRA_COPY, NOW));

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.ADOPTED, 1));
    assertThat(fake.chunksOn("worker1")).containsKey(chunk.chunkId());
    assertThat(status(chunk, "worker1")).isEqualTo(ReplicaStatus.HEALTHY);
    assertThat(tombstones.forWorker("worker1")).isEmpty();
  }

  @Test
  void neededCopyThatFailsVerificationLosesItsTombstoneButIsNeverDeleted() {
    ChunkRow chunk = seed(ObjectStatus.COMMITTED, Map.of("worker2", ReplicaStatus.HEALTHY));
    fakeHolds("worker1", chunk.chunkId(), bytesOf(chunk));
    fake.failReads("worker1", chunk.chunkId(), Failure.CORRUPT);
    tombstones.insert(
        new TombstoneRow(chunk.chunkId(), "worker1", TombstoneReason.EXTRA_COPY, NOW));

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.DROPPED, 1));
    assertThat(fake.chunksOn("worker1")).containsKey(chunk.chunkId());
  }

  @Test
  void failedDeleteKeepsTheTombstoneAndWorkersThatAreNotAliveAreSkipped() {
    String chunk = objectId(2) + "-000000";
    tombstones.insert(new TombstoneRow(chunk, "worker1", TombstoneReason.OBJECT_DELETED, NOW));
    tombstones.insert(new TombstoneRow(chunk, "worker2", TombstoneReason.OBJECT_DELETED, NOW));
    fake.stop("worker1");
    jdbc.sql("UPDATE workers SET state = 'DEAD' WHERE worker_id = 'worker2'").update();

    assertThat(tombstoneJob.runOnce()).containsExactly(Map.entry(Result.KEPT, 1));
    assertThat(tombstones.forObject(objectId(2))).hasSize(2);
  }

  // Reconciliation (§9)

  @Test
  void returningWorkersExtraCopiesAreTombstonedThenDeletedLeavingExactlyTwo() {
    // After repair: worker1 and worker3 HEALTHY; worker2 came back with its old LOST copy.
    ChunkRow chunk =
        seed(
            ObjectStatus.COMMITTED,
            Map.of(
                "worker1", ReplicaStatus.HEALTHY,
                "worker2", ReplicaStatus.LOST,
                "worker3", ReplicaStatus.HEALTHY));
    fakeHolds("worker2", chunk.chunkId(), bytesOf(chunk));

    assertThat(reconciler.reconcile("worker2")).isTrue();
    assertThat(tombstones.forWorker("worker2"))
        .extracting(TombstoneRow::chunkId, TombstoneRow::reason)
        .containsExactly(tuple(chunk.chunkId(), TombstoneReason.EXTRA_COPY));
    tombstoneJob.runOnce();

    assertThat(fake.chunksOn("worker2")).doesNotContainKey(chunk.chunkId());
    assertThat(replicas.replicasOf(chunk.chunkId()))
        .extracting(ReplicaRow::workerId)
        .containsExactly("worker1", "worker3");
  }

  @Test
  void returningWorkersStillNeededCopyIsVerifiedAndReadmitted() {
    ChunkRow chunk =
        seed(
            ObjectStatus.COMMITTED,
            Map.of("worker1", ReplicaStatus.HEALTHY, "worker2", ReplicaStatus.LOST));
    fakeHolds("worker2", chunk.chunkId(), bytesOf(chunk));

    reconciler.reconcile("worker2");

    assertThat(status(chunk, "worker2")).isEqualTo(ReplicaStatus.HEALTHY);
    assertThat(tombstones.forWorker("worker2")).isEmpty();
  }

  @Test
  void healthyCopyMissingFromTheInventoryIsMarkedLost() {
    ChunkRow chunk = seed(ObjectStatus.COMMITTED, Map.of("worker1", ReplicaStatus.HEALTHY));
    fake.deleteChunk(new WorkerRef("worker1", "u"), chunk.chunkId());

    reconciler.reconcile("worker1");

    assertThat(status(chunk, "worker1")).isEqualTo(ReplicaStatus.LOST);
  }

  @Test
  void inventoryThatCannotBeReadChangesNothing() {
    ChunkRow chunk = seed(ObjectStatus.COMMITTED, Map.of("worker1", ReplicaStatus.HEALTHY));
    fake.stop("worker1");

    assertThat(reconciler.reconcile("worker1")).isFalse();
    assertThat(status(chunk, "worker1")).isEqualTo(ReplicaStatus.HEALTHY);
  }

  @Test
  void startupGateBlocksTombstonesUntilEveryInventoryArrivesOrTheGracePasses() {
    reconciler = reconciler(false); // Started now: gate closed.
    ChunkRow chunk =
        seed(
            ObjectStatus.COMMITTED,
            Map.of(
                "worker1", ReplicaStatus.HEALTHY,
                "worker2", ReplicaStatus.LOST,
                "worker3", ReplicaStatus.HEALTHY));
    fakeHolds("worker2", chunk.chunkId(), bytesOf(chunk));

    reconciler.reconcile("worker2");
    assertThat(tombstones.forWorker("worker2")).as("gate closed").isEmpty();

    clock.advance(STARTUP_GRACE);
    reconciler.reconcile("worker2");
    assertThat(tombstones.forWorker("worker2")).as("grace passed").hasSize(1);
  }

  @Test
  void untrackedCopyOfALiveObjectIsTombstoned() {
    ChunkRow chunk = seed(ObjectStatus.COMMITTED, Map.of("worker1", ReplicaStatus.HEALTHY));
    fakeHolds("worker1", chunk.chunkId(), bytesOf(chunk));
    fakeHolds("worker4", chunk.chunkId(), bytesOf(chunk));

    reconciler.reconcile("worker4");

    assertThat(tombstones.forWorker("worker4"))
        .extracting(TombstoneRow::reason)
        .containsExactly(TombstoneReason.UNTRACKED_COPY);
  }

  @Test
  void orphanIsTombstonedOnlyAfterTheGracePeriodAndNeverForAnUploadingObject() {
    String orphan = objectId(7) + "-000000";
    fakeHolds("worker1", orphan, bytes(10));
    ChunkRow uploading = seed(ObjectStatus.UPLOADING, Map.of());
    fakeHolds("worker1", uploading.chunkId(), bytesOf(uploading));

    reconciler.reconcile("worker1");
    clock.advance(GC_GRACE.minusSeconds(1));
    reconciler.reconcile("worker1");
    assertThat(tombstones.forWorker("worker1")).as("within the grace period").isEmpty();

    clock.advance(Duration.ofSeconds(1));
    reconciler.reconcile("worker1");
    assertThat(tombstones.forWorker("worker1"))
        .extracting(TombstoneRow::chunkId, TombstoneRow::reason)
        .containsExactly(tuple(orphan, TombstoneReason.ORPHAN));
  }

  @Test
  void dryRunOnlyLogsOrphans() {
    reconciler = reconciler(true);
    clock.advance(STARTUP_GRACE);
    fakeHolds("worker1", objectId(8) + "-000000", bytes(10));

    reconciler.reconcile("worker1");
    clock.advance(GC_GRACE);
    reconciler.reconcile("worker1");

    assertThat(tombstones.forWorker("worker1")).isEmpty();
  }

  /**
   * Ignores the extra runs that reconciliation triggers, so a tombstone job never runs on another
   * thread while a test is still asserting; tests run each job with {@code runOnce()} instead.
   */
  private static final class ManualScheduler extends ThreadPoolTaskScheduler {

    private static final long serialVersionUID = 1L;

    @Override
    public void execute(Runnable task) {
      // Deliberately dropped: see the class comment.
    }
  }

  private Reconciler reconciler(boolean dryRun) {
    ControllerProperties properties =
        new ControllerProperties(Duration.ofSeconds(3), 3, 5, 4, GC_GRACE, STARTUP_GRACE, dryRun);
    return new Reconciler(
        workers,
        replicas,
        objects,
        tombstones,
        fake,
        transactions,
        event -> {},
        new OrphanTracker(properties),
        tombstoneJob,
        scheduler,
        clock,
        properties);
  }

  /** Seeds a one-chunk object with copy rows; the fake holds the bytes of every HEALTHY copy. */
  private ChunkRow seed(ObjectStatus status, Map<String, ReplicaStatus> copies) {
    String id =
        objectId(100 + jdbc.sql("SELECT count(*) FROM objects").query(Integer.class).single());
    byte[] bytes = bytes(1000);
    ChunkRow chunk = new ChunkRow(id + "-000000", id, 0, bytes.length, Checksums.sha256Hex(bytes));
    boolean committed = status == ObjectStatus.COMMITTED;
    objects.insert(
        new ObjectRow(
            id,
            "r.bin",
            committed ? (long) bytes.length : null,
            "application/octet-stream",
            committed ? chunk.checksumSha256() : null,
            committed ? 1 : null,
            status,
            NOW,
            NOW,
            "{}"));
    objects.insertChunk(chunk);
    copies.forEach((worker, copyStatus) -> {
      replicas.insert(new ReplicaRow(chunk.chunkId(), worker, copyStatus, NOW));
      if (copyStatus == ReplicaStatus.HEALTHY) {
        fakeHolds(worker, chunk.chunkId(), bytes);
      }
    });
    return chunk;
  }

  private void fakeHolds(String workerId, String chunkId, byte[] bytes) {
    fake.putChunk(new WorkerRef(workerId, "u"), chunkId, bytes, bytes.length, "");
  }

  /** The bytes every seeded chunk holds. */
  private static byte[] bytesOf(ChunkRow chunk) {
    return bytes(chunk.size());
  }

  private static byte[] bytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private ReplicaStatus status(ChunkRow chunk, String workerId) {
    return replicas.replicasOf(chunk.chunkId()).stream()
        .filter(row -> row.workerId().equals(workerId))
        .findFirst()
        .orElseThrow()
        .status();
  }
}
