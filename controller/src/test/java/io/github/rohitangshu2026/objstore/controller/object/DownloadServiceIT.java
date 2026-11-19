package io.github.rohitangshu2026.objstore.controller.object;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.common.UuidV7;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.SqliteTestBase;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
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
 * Downloads against a real SQLite file and in-memory workers that fail on demand. Objects are
 * stored through the real {@link UploadService}, then read back with copies corrupted, missing or
 * unreachable.
 */
class DownloadServiceIT extends SqliteTestBase {

  @Autowired JdbcClient jdbc;
  @Autowired ObjectRepository objects;
  @Autowired ReplicaRepository replicas;
  @Autowired TombstoneRepository tombstones;
  @Autowired WorkerRepository workers;
  @Autowired TransactionTemplate transactions;

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
  private final FakeWorkerClient fake = new FakeWorkerClient();
  private ThreadPoolTaskExecutor replicaWrites;
  private UploadService uploads;
  private DownloadService downloads;

  @BeforeEach
  void setUp() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    for (int i = 1; i <= 4; i++) {
      workers.insert(
          new WorkerRow("worker" + i, "http://worker" + i + ":9000", WorkerState.ALIVE, NOW, NOW));
    }
    replicaWrites = new ThreadPoolTaskExecutor();
    replicaWrites.setCorePoolSize(2);
    replicaWrites.initialize();
    ObjectRemoval removal =
        new ObjectRemoval(objects, replicas, tombstones, workers, fake, transactions, clock);
    uploads =
        new UploadService(
            objects,
            replicas,
            workers,
            removal,
            new InFlightObjects(),
            fake,
            replicaWrites,
            transactions,
            new UuidV7(clock, new Random(3)),
            clock);
    downloads = new DownloadService(objects, replicas, fake, transactions, event -> {});
  }

  @AfterEach
  void tearDown() {
    replicaWrites.shutdown();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, CHUNK_SIZE + 1, 20 * CHUNK_SIZE})
  void roundTripReturnsTheExactBytes(int size) throws IOException {
    byte[] content = bytes(size);
    ObjectRow object = upload(content);

    assertThat(download(object.objectId())).isEqualTo(content);
  }

  @Test
  void copyCorruptedBehindTheWorkersBackIsMarkedCorruptAndTheOtherCopyServed() throws IOException {
    byte[] content = bytes(2 * CHUNK_SIZE);
    ObjectRow object = upload(content);
    ReplicaRow first = copies(object, 0).get(0);
    fake.corruptSilently(first.workerId(), first.chunkId());

    assertThat(download(object.objectId())).isEqualTo(content);
    assertThat(status(first)).isEqualTo(ReplicaStatus.CORRUPT);
  }

  @Test
  void workerReportingCorruptionOrAMissingCopyGetsTheCopyMarked() throws IOException {
    byte[] content = bytes(2 * CHUNK_SIZE);
    ObjectRow object = upload(content);
    ReplicaRow quarantined = copies(object, 0).get(0);
    ReplicaRow missing = copies(object, 1).get(0);
    fake.failReads(quarantined.workerId(), quarantined.chunkId(), Failure.CORRUPT);
    fake.failReads(missing.workerId(), missing.chunkId(), Failure.NOT_FOUND);

    assertThat(download(object.objectId())).isEqualTo(content);
    assertThat(status(quarantined)).isEqualTo(ReplicaStatus.CORRUPT);
    assertThat(status(missing)).isEqualTo(ReplicaStatus.LOST);
  }

  @Test
  void unreachableCopyIsSkippedWithoutChangingItsStatus() throws IOException {
    byte[] content = bytes(CHUNK_SIZE);
    ObjectRow object = upload(content);
    ReplicaRow first = copies(object, 0).get(0);
    fake.stop(first.workerId());

    assertThat(download(object.objectId())).isEqualTo(content);
    assertThat(status(first)).isEqualTo(ReplicaStatus.HEALTHY);
  }

  @Test
  void aliveCopiesAreTriedBeforeSuspectOnes() throws IOException {
    byte[] content = bytes(CHUNK_SIZE);
    ObjectRow object = upload(content);
    List<ReplicaRow> both = copies(object, 0);
    // The copy that would come first by worker ID is on a SUSPECT worker; it must not be read.
    jdbc.sql("UPDATE workers SET state = 'SUSPECT' WHERE worker_id = ?")
        .param(both.get(0).workerId())
        .update();
    fake.failReads(both.get(0).workerId(), both.get(0).chunkId(), Failure.CORRUPT);

    assertThat(download(object.objectId())).isEqualTo(content);
    assertThat(status(both.get(0))).as("never read").isEqualTo(ReplicaStatus.HEALTHY);
  }

  @Test
  void chunkWithNoCopyOnALiveWorkerIs503BeforeAnythingIsSent() {
    ObjectRow object = upload(bytes(2 * CHUNK_SIZE));
    for (ReplicaRow copy : copies(object, 1)) {
      jdbc.sql("UPDATE workers SET state = 'DEAD' WHERE worker_id = ?")
          .param(copy.workerId())
          .update();
    }

    assertError(object.objectId(), ErrorCode.CHUNK_UNAVAILABLE);
  }

  @Test
  void unknownObjectIs404AndAnUploadInProgressIs409() {
    assertError("019a8f2e-0000-7000-8000-000000000000", ErrorCode.OBJECT_NOT_FOUND);
    ObjectRow object = upload(bytes(1));
    jdbc.sql("UPDATE objects SET status = 'UPLOADING' WHERE object_id = ?")
        .param(object.objectId())
        .update();
    assertError(object.objectId(), ErrorCode.OBJECT_NOT_COMMITTED);
  }

  @Test
  void everyCopyOfALaterChunkFailingAbortsAfterTheEarlierChunks() {
    byte[] content = bytes(4 * CHUNK_SIZE);
    ObjectRow object = upload(content);
    for (ReplicaRow copy : copies(object, 2)) {
      fake.failReads(copy.workerId(), copy.chunkId(), Failure.OUTCOME_UNKNOWN);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThatExceptionOfType(DownloadAbortedException.class)
        .isThrownBy(() -> downloads.write(downloads.prepare(object.objectId()), out));
    assertThat(out.toByteArray()).isEqualTo(Arrays.copyOf(content, 2 * CHUNK_SIZE));
  }

  @Test
  void wrongWholeObjectChecksumAbortsBeforeTheLastChunk() {
    byte[] content = bytes(3 * CHUNK_SIZE);
    ObjectRow object = upload(content);
    jdbc.sql("UPDATE objects SET checksum_sha256 = ? WHERE object_id = ?")
        .param("0".repeat(64))
        .param(object.objectId())
        .update();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThatExceptionOfType(DownloadAbortedException.class)
        .isThrownBy(() -> downloads.write(downloads.prepare(object.objectId()), out));
    assertThat(out.toByteArray()).isEqualTo(Arrays.copyOf(content, 2 * CHUNK_SIZE));
  }

  private ObjectRow upload(byte[] content) {
    return uploads.upload(
        uploads.aliveWorkers(),
        "o.bin",
        "application/octet-stream",
        "{}",
        new ByteArrayInputStream(content));
  }

  private byte[] download(String objectId) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    downloads.write(downloads.prepare(objectId), out);
    return out.toByteArray();
  }

  private void assertError(String objectId, ErrorCode code) {
    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(() -> downloads.prepare(objectId))
        .extracting(ApiException::code)
        .isEqualTo(code);
  }

  /** The copies of one chunk, sorted by worker ID, which is the order a download tries them. */
  private List<ReplicaRow> copies(ObjectRow object, int index) {
    return replicas.replicasOf(objects.chunksOf(object.objectId()).get(index).chunkId());
  }

  private ReplicaStatus status(ReplicaRow copy) {
    return replicas.replicasOf(copy.chunkId()).stream()
        .filter(row -> row.workerId().equals(copy.workerId()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static byte[] bytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }
}
