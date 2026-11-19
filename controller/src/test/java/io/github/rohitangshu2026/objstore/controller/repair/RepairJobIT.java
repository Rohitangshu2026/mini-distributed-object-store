package io.github.rohitangshu2026.objstore.controller.repair;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.common.UuidV7;
import io.github.rohitangshu2026.objstore.common.api.ReplicationReport;
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
import io.github.rohitangshu2026.objstore.controller.object.UploadService;
import io.github.rohitangshu2026.objstore.controller.repair.RepairJob.Outcome;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repair against a real SQLite file and in-memory workers that fail on demand. Every repaired chunk
 * is checked for exactly 2 HEALTHY copies on different ALIVE workers holding the right bytes, and
 * the replication report is checked to agree.
 */
class RepairJobIT extends SqliteTestBase {

  private static final List<String> WORKERS = List.of("worker1", "worker2", "worker3", "worker4");

  @Autowired JdbcClient jdbc;
  @Autowired ObjectRepository objects;
  @Autowired ReplicaRepository replicas;
  @Autowired TombstoneRepository tombstones;
  @Autowired WorkerRepository workers;
  @Autowired TransactionTemplate transactions;

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
  private final FakeWorkerClient fake = new FakeWorkerClient();
  private ThreadPoolTaskExecutor pool;
  private ThreadPoolTaskScheduler scheduler;
  private UploadService uploads;
  private RepairJob repair;
  private ReplicationReporter reporter;

  @BeforeEach
  void setUp() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    WORKERS.forEach(id ->
        workers.insert(new WorkerRow(id, "http://" + id + ":9000", WorkerState.ALIVE, NOW, NOW)));
    pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(4);
    pool.initialize();
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    uploads =
        new UploadService(
            objects,
            replicas,
            workers,
            new ObjectRemoval(objects, replicas, tombstones, workers, fake, transactions, clock),
            new InFlightObjects(),
            fake,
            pool,
            transactions,
            new UuidV7(clock, new Random(11)),
            clock);
    repair =
        new RepairJob(replicas, tombstones, workers, fake, pool, scheduler, transactions, clock);
    reporter = new ReplicationReporter(replicas, tombstones);
  }

  @AfterEach
  void tearDown() {
    pool.shutdown();
    scheduler.shutdown();
  }

  @Test
  void deadWorkersChunksReturnToTwoHealthyCopiesElsewhere() {
    ObjectRow object = upload(6 * CHUNK_SIZE);
    workerDies("worker2");
    ReplicationReport before = reporter.report();
    assertThat(before.underReplicated()).isPositive();
    assertThat(before.healthy() + before.underReplicated()).isEqualTo(6);

    Map<Outcome, Integer> outcomes = repair.runOnce();

    assertThat(outcomes).containsOnlyKeys(Outcome.REPAIRED);
    assertFullyReplicatedWithout(object, "worker2");
    ReplicationReport after = reporter.report();
    assertThat(after.healthy()).isEqualTo(6);
    assertThat(after.underReplicated()).isZero();
    assertThat(after.unavailable()).isZero();
    assertThat(after.chunks()).isEmpty();
  }

  @Test
  void withTwoWorkersLeftChunksWithASourceAreRepairedAndTheRestReportedUnavailable() {
    upload(8 * CHUNK_SIZE);
    workerDies("worker3");
    workerDies("worker4");

    repair.runOnce();

    ReplicationReport report = reporter.report();
    assertThat(report.underReplicated()).isZero();
    assertThat(report.healthy() + report.unavailable()).isEqualTo(8);
    // A chunk whose only copies were on worker3 and worker4 has nothing to repair from: it is
    // reported unavailable, never healthy (I3).
    assertThat(report.chunks())
        .allSatisfy(chunk -> {
          assertThat(chunk.healthyCopies()).isZero();
          assertThat(chunk.replicas()).allMatch(copy -> copy.workerState().equals("DEAD"));
        });
  }

  @Test
  void noDestinationLeavesTheChunkUnderReplicatedForTheNextRun() {
    ChunkRow chunk = seedChunk(ReplicaStatus.HEALTHY, ReplicaStatus.LOST);
    workerDies("worker2");
    workerDies("worker3");
    workerDies("worker4");

    assertThat(repair.runOnce()).containsExactly(Map.entry(Outcome.UNDER_REPLICATED, 1));
    assertThat(reporter.report().underReplicated()).isEqualTo(1);

    jdbc.sql("UPDATE workers SET state = 'ALIVE' WHERE worker_id = 'worker3'").update();
    fake.start("worker3");
    assertThat(repair.runOnce()).containsExactly(Map.entry(Outcome.REPAIRED, 1));
    assertThat(status(chunk, "worker3")).isEqualTo(ReplicaStatus.HEALTHY);
  }

  @Test
  void corruptOnlySourceIsMarkedCorruptAndTheChunkReportedUnavailable() {
    ChunkRow chunk = seedChunk(ReplicaStatus.HEALTHY, ReplicaStatus.LOST);
    workerDies("worker2");
    fake.corruptSilently("worker1", chunk.chunkId());

    assertThat(repair.runOnce()).containsExactly(Map.entry(Outcome.UNAVAILABLE, 1));
    assertThat(status(chunk, "worker1")).isEqualTo(ReplicaStatus.CORRUPT);
    assertThat(reporter.report().unavailable()).isEqualTo(1);
  }

  @Test
  void failedDestinationBecomesATombstoneAndTheNextDestinationIsUsed() {
    ChunkRow chunk = seedChunk(ReplicaStatus.HEALTHY, ReplicaStatus.LOST);
    workerDies("worker2");
    // Placement prefers worker3 (same load, earlier in the rotation for chunk 0).
    fake.stop("worker3");

    assertThat(repair.runOnce()).containsExactly(Map.entry(Outcome.REPAIRED, 1));

    assertThat(status(chunk, "worker4")).isEqualTo(ReplicaStatus.HEALTHY);
    assertThat(tombstones.forWorker("worker3"))
        .extracting(TombstoneRow::chunkId, TombstoneRow::reason)
        .containsExactly(tuple(chunk.chunkId(), TombstoneReason.WRITE_FAILED));
  }

  @Test
  void corruptCopyOnALiveWorkerIsRewrittenInPlace() {
    ChunkRow chunk = seedChunk(ReplicaStatus.HEALTHY, ReplicaStatus.CORRUPT);

    assertThat(repair.runOnce()).containsExactly(Map.entry(Outcome.REPAIRED, 1));

    assertThat(status(chunk, "worker2")).isEqualTo(ReplicaStatus.HEALTHY);
    assertThat(replicas.replicasOf(chunk.chunkId())).hasSize(2);
    assertThat(Checksums.sha256Hex(fake.chunksOn("worker2").get(chunk.chunkId())))
        .isEqualTo(chunk.checksumSha256());
  }

  @Test
  void chunksOfObjectsThatAreNotCommittedAreLeftAlone() {
    ChunkRow chunk = seedChunk(ReplicaStatus.HEALTHY, ReplicaStatus.LOST);
    jdbc.sql("UPDATE objects SET status = 'DELETING'").update();

    assertThat(repair.runOnce()).isEmpty();
    assertThat(replicas.replicasOf(chunk.chunkId())).hasSize(2);
  }

  private ObjectRow upload(int size) {
    byte[] content = new byte[size];
    new Random(size).nextBytes(content);
    return uploads.upload(
        uploads.aliveWorkers(),
        "o.bin",
        "application/octet-stream",
        "{}",
        new ByteArrayInputStream(content));
  }

  /** What membership does when a worker goes DEAD, plus the worker no longer answering. */
  private void workerDies(String workerId) {
    jdbc.sql("UPDATE workers SET state = 'DEAD' WHERE worker_id = ?").param(workerId).update();
    replicas.markLostOn(workerId);
    fake.stop(workerId);
  }

  /**
   * Seeds a committed one-chunk object with a copy on worker1 (whose bytes the fake holds) and a
   * copy on worker2.
   */
  private ChunkRow seedChunk(ReplicaStatus onWorker1, ReplicaStatus onWorker2) {
    byte[] bytes = new byte[1000];
    new Random(1).nextBytes(bytes);
    String id = objectId(1);
    ChunkRow chunk = new ChunkRow(id + "-000000", id, 0, bytes.length, Checksums.sha256Hex(bytes));
    objects.insert(
        new ObjectRow(
            id,
            "s.bin",
            (long) bytes.length,
            "application/octet-stream",
            chunk.checksumSha256(),
            1,
            ObjectStatus.COMMITTED,
            NOW,
            NOW,
            "{}"));
    objects.insertChunk(chunk);
    replicas.insert(new ReplicaRow(chunk.chunkId(), "worker1", onWorker1, NOW));
    replicas.insert(new ReplicaRow(chunk.chunkId(), "worker2", onWorker2, NOW));
    fake.putChunk(new WorkerRef("worker1", "u"), chunk.chunkId(), bytes, bytes.length, "");
    return chunk;
  }

  private void assertFullyReplicatedWithout(ObjectRow object, String deadWorker) {
    for (ChunkRow chunk : objects.chunksOf(object.objectId())) {
      List<ReplicaRow> healthy =
          replicas.replicasOf(chunk.chunkId()).stream()
              .filter(r -> r.status() == ReplicaStatus.HEALTHY)
              .toList();
      assertThat(healthy).hasSize(2).noneMatch(r -> r.workerId().equals(deadWorker));
      assertThat(healthy.get(0).workerId()).isNotEqualTo(healthy.get(1).workerId());
      for (ReplicaRow copy : healthy) {
        assertThat(Checksums.sha256Hex(fake.chunksOn(copy.workerId()).get(chunk.chunkId())))
            .isEqualTo(chunk.checksumSha256());
      }
    }
  }

  private ReplicaStatus status(ChunkRow chunk, String workerId) {
    return replicas.replicasOf(chunk.chunkId()).stream()
        .filter(row -> row.workerId().equals(workerId))
        .findFirst()
        .orElseThrow()
        .status();
  }
}
