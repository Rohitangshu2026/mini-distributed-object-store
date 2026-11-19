package io.github.rohitangshu2026.objstore.controller.object;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.common.UuidV7;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
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
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uploads against a real SQLite file and in-memory workers that can fail on demand. Every stored
 * object is checked against the brief: chunks of {@code CHUNK_SIZE} in order, each with exactly 2
 * HEALTHY copies on different workers that hold the right bytes.
 */
class UploadServiceIT extends SqliteTestBase {

  private static final List<String> WORKERS = List.of("worker1", "worker2", "worker3", "worker4");

  @Autowired JdbcClient jdbc;
  @Autowired ObjectRepository objects;
  @Autowired ReplicaRepository replicas;
  @Autowired TombstoneRepository tombstones;
  @Autowired WorkerRepository workers;
  @Autowired TransactionTemplate transactions;

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
  private final FakeWorkerClient fake = new FakeWorkerClient();
  private final InFlightObjects inFlight = new InFlightObjects();
  private ThreadPoolTaskExecutor replicaWrites;
  private UploadService uploads;

  @BeforeEach
  void setUp() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    WORKERS.forEach(id ->
        workers.insert(new WorkerRow(id, "http://" + id + ":9000", WorkerState.ALIVE, NOW, NOW)));
    replicaWrites = new ThreadPoolTaskExecutor();
    replicaWrites.setCorePoolSize(8);
    replicaWrites.setMaxPoolSize(8);
    replicaWrites.setQueueCapacity(256);
    replicaWrites.initialize();
    ObjectRemoval removal =
        new ObjectRemoval(objects, replicas, tombstones, workers, fake, transactions, clock);
    uploads =
        new UploadService(
            objects,
            replicas,
            workers,
            removal,
            inFlight,
            fake,
            replicaWrites,
            transactions,
            new UuidV7(clock, new Random(7)),
            clock);
  }

  @AfterEach
  void tearDown() {
    replicaWrites.shutdown();
  }

  @Test
  void fiveChunksGetTenHealthyCopiesOnDistinctWorkersUsingAllFour() {
    byte[] content = bytes(5 * CHUNK_SIZE, 1);

    ObjectRow object = upload("five.bin", content);

    assertStoredCorrectly(object, content);
    assertThat(object.chunkCount()).isEqualTo(5);
    Set<String> usedWorkers = new HashSet<>();
    allReplicas().forEach(replica -> usedWorkers.add(replica.workerId()));
    assertThat(allReplicas()).hasSize(10);
    assertThat(usedWorkers).containsExactlyInAnyOrderElementsOf(WORKERS);
    assertThat(object.name()).isEqualTo("five.bin");
    assertThat(object.customMetadata()).isEqualTo("{\"k\":1}");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, CHUNK_SIZE, CHUNK_SIZE + 1})
  void boundarySizesGetTheRightChunks(int size) {
    byte[] content = bytes(size, size);

    ObjectRow object = upload("b.bin", content);

    assertStoredCorrectly(object, content);
    assertThat(object.chunkCount()).isEqualTo((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
  }

  @Test
  void objectWithoutANameIsNamedByItsId() {
    ObjectRow object =
        uploads.upload(uploads.aliveWorkers(), null, "a/b", "{}", stream(bytes(1, 1)));

    assertThat(object.name()).isEqualTo(object.objectId());
  }

  @Test
  void fewerThanTwoAliveWorkersIsRefusedBeforeAnythingIsStored() {
    jdbc.sql("UPDATE workers SET state = 'DEAD' WHERE worker_id <> 'worker1'").update();

    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(uploads::aliveWorkers)
        .extracting(ApiException::code)
        .isEqualTo(ErrorCode.INSUFFICIENT_WORKERS);
    assertThat(count("objects")).isZero();
  }

  @Test
  void copyOnAnUnreachableWorkerIsPlacedElsewhereAndTombstoned() {
    fake.stop("worker1");
    byte[] content = bytes(4 * CHUNK_SIZE, 2);

    ObjectRow object = upload("x.bin", content);

    assertStoredCorrectly(object, content);
    assertThat(allReplicas()).noneMatch(replica -> replica.workerId().equals("worker1"));
    List<TombstoneRow> failed = tombstones.forWorker("worker1");
    assertThat(failed).isNotEmpty().allMatch(t -> t.reason() == TombstoneReason.WRITE_FAILED);
  }

  @Test
  void writeWhoseAnswerIsLostIsTombstonedAndPlacedElsewhere() {
    fake.loseWriteAnswers("worker2");
    byte[] content = bytes(4 * CHUNK_SIZE, 3);

    ObjectRow object = upload("y.bin", content);

    assertStoredCorrectly(object, content);
    assertThat(allReplicas()).noneMatch(replica -> replica.workerId().equals("worker2"));
    // The copies did land, so their tombstones are what will remove them.
    assertThat(tombstones.forWorker("worker2"))
        .extracting(TombstoneRow::chunkId)
        .containsExactlyInAnyOrderElementsOf(fake.chunksOn("worker2").keySet());
  }

  @Test
  void runningOutOfWorkersAbortsTheUploadAndLeavesNoObject() {
    fake.stop("worker2");
    fake.stop("worker3");
    fake.stop("worker4");

    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(() -> upload("z.bin", bytes(2 * CHUNK_SIZE, 4)))
        .extracting(ApiException::code)
        .isEqualTo(ErrorCode.INSUFFICIENT_WORKERS);

    assertThat(count("objects")).isZero();
    assertThat(count("chunk_replicas")).isZero();
    // The copy on worker1 was deleted by the abort; the unreachable ones keep their tombstones.
    assertThat(fake.chunksOn("worker1")).isEmpty();
    assertThat(tombstones.forWorker("worker1")).isEmpty();
    assertThat(count("tombstones")).isPositive();
  }

  @Test
  void eightParallelUploadsAllSucceedWithDistinctCopies() throws Exception {
    ExecutorService clients = Executors.newFixedThreadPool(8);
    try {
      List<Future<ObjectRow>> results = new ArrayList<>();
      List<byte[]> contents = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        byte[] content = bytes(2 * CHUNK_SIZE + i, 10 + i);
        contents.add(content);
        Callable<ObjectRow> task = () -> upload("p" + content.length, content);
        results.add(clients.submit(task));
      }
      for (int i = 0; i < 8; i++) {
        assertStoredCorrectly(results.get(i).get(), contents.get(i));
      }
    } finally {
      clients.shutdownNow();
    }
    // The second copies never ran on more threads than the pool has.
    assertThat(replicaWrites.getThreadPoolExecutor().getLargestPoolSize()).isLessThanOrEqualTo(8);
  }

  private ObjectRow upload(String name, byte[] content) {
    return uploads.upload(
        uploads.aliveWorkers(), name, "application/octet-stream", "{\"k\":1}", stream(content));
  }

  /**
   * Checks the brief's guarantees for one object: COMMITTED with the right totals; chunks in order
   * of {@code CHUNK_SIZE}; each with exactly 2 HEALTHY copies on different workers holding exactly
   * its bytes.
   */
  private void assertStoredCorrectly(ObjectRow object, byte[] content) {
    ObjectRow stored = objects.find(object.objectId()).orElseThrow();
    assertThat(stored.status()).isEqualTo(ObjectStatus.COMMITTED);
    assertThat(stored.size()).isEqualTo(content.length);
    assertThat(stored.checksumSha256()).isEqualTo(Checksums.sha256Hex(content));
    ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
    List<ChunkRow> chunks = objects.chunksOf(object.objectId());
    assertThat(chunks).hasSize(stored.chunkCount());
    for (ChunkRow chunk : chunks) {
      assertThat(chunk.size()).isLessThanOrEqualTo(CHUNK_SIZE);
      List<ReplicaRow> copies = replicas.replicasOf(chunk.chunkId());
      assertThat(copies).hasSize(2).allMatch(r -> r.status() == ReplicaStatus.HEALTHY);
      assertThat(copies.get(0).workerId()).isNotEqualTo(copies.get(1).workerId());
      for (ReplicaRow copy : copies) {
        byte[] held = fake.chunksOn(copy.workerId()).get(chunk.chunkId());
        assertThat(held).isNotNull();
        assertThat(Checksums.sha256Hex(held)).isEqualTo(chunk.checksumSha256());
      }
      reassembled.writeBytes(fake.chunksOn(copies.get(0).workerId()).get(chunk.chunkId()));
    }
    assertThat(reassembled.toByteArray()).isEqualTo(content);
  }

  private List<ReplicaRow> allReplicas() {
    return jdbc.sql("SELECT chunk_id, worker_id, status, last_verified_at FROM chunk_replicas")
        .query(ReplicaRow.class)
        .list();
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }

  private static ByteArrayInputStream stream(byte[] content) {
    return new ByteArrayInputStream(content);
  }

  private static byte[] bytes(int size, long seed) {
    byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }
}
