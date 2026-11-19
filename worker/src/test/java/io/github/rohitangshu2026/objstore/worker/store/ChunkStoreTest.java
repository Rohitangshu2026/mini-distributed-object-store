package io.github.rohitangshu2026.objstore.worker.store;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.common.api.ChunkInfo;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link ChunkStore} on a real temp directory, grouped by category: normal behaviour,
 * boundaries and invalid input, expected and partial failures, repeats, restarts, and concurrency.
 */
class ChunkStoreTest {

  private static final String OBJECT_ID = "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f";
  private static final ChunkId ID = chunk(0);

  @TempDir Path dataDir;

  private ChunkStore store;

  @BeforeEach
  void open() {
    store = reopen();
  }

  // Normal behaviour

  @ParameterizedTest
  @ValueSource(ints = {1, CHUNK_SIZE})
  void putThenGetReturnsTheSameBytes(int size) {
    byte[] data = bytes(size, size);

    ChunkInfo info = put(ID, data);

    assertThat(info).isEqualTo(new ChunkInfo(ID.value(), size, sha(data)));
    assertThat(store.get(ID).bytes()).isEqualTo(data);
    assertThat(store.get(ID).checksum()).isEqualTo(sha(data));
  }

  @Test
  void fileIsNamedAfterItsChecksumInAHashedDirectory() throws IOException {
    byte[] data = bytes(100, 1);

    put(ID, data);

    assertThat(chunkFiles()).containsExactly(directoryOf(ID).resolve(ID + "." + sha(data)));
  }

  @Test
  void inventoryListsEachChunkWithItsSizeAndRecordedChecksum() {
    byte[] first = bytes(10, 1);
    byte[] second = bytes(20, 2);
    put(chunk(1), second);
    put(chunk(0), first);

    assertThat(store.chunks())
        .containsExactly(
            new ChunkInfo(chunk(0).value(), 10, sha(first)),
            new ChunkInfo(chunk(1).value(), 20, sha(second)));
    assertThat(store.chunkCount()).isEqualTo(2);
  }

  @Test
  void deleteRemovesTheChunk() throws IOException {
    put(ID, bytes(10, 1));

    store.delete(ID);

    assertApiError(() -> store.get(ID), ErrorCode.CHUNK_NOT_FOUND);
    assertThat(chunkFiles()).isEmpty();
    assertThat(store.chunks()).isEmpty();
  }

  // Boundary conditions and invalid input

  @Test
  void bodyOverChunkSizeIsRejectedAndNothingIsStored() throws IOException {
    assertApiError(() -> put(ID, bytes(CHUNK_SIZE + 1, 3)), ErrorCode.INVALID_CHUNK_SIZE);
    assertThat(chunkFiles()).isEmpty();
  }

  @Test
  void emptyBodyIsRejectedAndNothingIsStored() throws IOException {
    assertApiError(() -> put(ID, new byte[0]), ErrorCode.INVALID_CHUNK_SIZE);
    assertThat(chunkFiles()).isEmpty();
  }

  // Expected failures

  @Test
  void bodyThatDoesNotMatchItsChecksumIsRejectedAndNothingIsStored() throws IOException {
    byte[] data = bytes(10, 1);

    assertApiError(
        () -> store.put(ID, sha(bytes(10, 2)), new ByteArrayInputStream(data)),
        ErrorCode.BODY_CHECKSUM_MISMATCH);
    assertThat(chunkFiles()).isEmpty();
  }

  @Test
  void corruptCopyIsQuarantinedAndThenNotFound() throws IOException {
    put(ID, bytes(1000, 4));
    Path file = storedFile(ID);
    flipFirstByte(file);

    assertApiError(() -> store.get(ID), ErrorCode.STORED_CHECKSUM_MISMATCH);

    assertThat(chunkFiles()).containsExactly(file.resolveSibling(file.getFileName() + ".corrupt"));
    assertThat(store.chunkCount()).isZero();
    assertThat(store.quarantined()).containsExactly(ID.value());
    assertApiError(() -> store.get(ID), ErrorCode.CHUNK_NOT_FOUND);
  }

  // Partial failures

  @Test
  void diskFailureOnWriteIsStorageUnavailableAndLeavesNoTempFile() throws IOException {
    // A regular file where the chunk's directory must go fails the write, even when run as root.
    Path blocker = directoryOf(ID).getParent();
    Files.writeString(blocker, "not a directory");

    assertApiError(() -> put(ID, bytes(10, 5)), ErrorCode.STORAGE_UNAVAILABLE);
    assertThat(chunkFiles()).containsExactly(blocker);
  }

  @Test
  void diskFailureOnReadIsStorageUnavailable() throws IOException {
    put(ID, bytes(10, 5));
    Path file = storedFile(ID);
    // A directory in place of the file fails the read, even when run as root.
    Files.delete(file);
    Files.createDirectory(file);

    assertApiError(() -> store.get(ID), ErrorCode.STORAGE_UNAVAILABLE);
  }

  @Test
  void bodyThatFailsMidwayIsRejectedAndLeavesNoTempFile() throws IOException {
    InputStream connectionReset =
        new SequenceInputStream(
            new ByteArrayInputStream(bytes(100, 1)),
            new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("Connection reset");
              }

              @Override
              public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("Connection reset");
              }
            });

    assertApiError(
        () -> store.put(ID, sha(bytes(10, 1)), connectionReset), ErrorCode.INVALID_REQUEST);
    assertThat(chunkFiles()).isEmpty();
  }

  // Failover and repeat

  @Test
  void samePutTwiceLeavesOneFile() throws IOException {
    byte[] data = bytes(10, 1);

    assertThat(put(ID, data)).isEqualTo(put(ID, data));
    assertThat(chunkFiles()).hasSize(1);
  }

  @Test
  void deleteTwiceAndDeleteOfAnUnknownChunkSucceed() {
    put(ID, bytes(10, 1));

    store.delete(ID);
    store.delete(ID);
    store.delete(chunk(9));

    assertThat(store.chunkCount()).isZero();
  }

  @Test
  void putAfterQuarantineMakesTheChunkServableAndRemovesTheCorruptFile() throws IOException {
    byte[] data = bytes(1000, 4);
    put(ID, data);
    flipFirstByte(storedFile(ID));
    assertApiError(() -> store.get(ID), ErrorCode.STORED_CHECKSUM_MISMATCH);

    put(ID, data);

    assertThat(store.get(ID).bytes()).isEqualTo(data);
    assertThat(chunkFiles()).containsExactly(storedFile(ID));
    assertThat(store.quarantined()).isEmpty();
  }

  // Recovery and restart

  @Test
  void bootDeletesUnfinishedWrites() throws IOException {
    byte[] data = bytes(10, 1);
    put(ID, data);
    Path leftover = directoryOf(ID).resolve(ID + ".123456789.tmp");
    Files.writeString(leftover, "partial");

    store = reopen();

    assertThat(leftover).doesNotExist();
    assertThat(store.get(ID).bytes()).isEqualTo(data);
  }

  @Test
  void bootRebuildsTheSameIndex() {
    for (int index = 0; index < 3; index++) {
      put(chunk(index), bytes(10 + index, index));
    }
    List<ChunkInfo> before = store.chunks();

    store = reopen();

    assertThat(store.chunks()).isEqualTo(before);
  }

  @Test
  void bootLeavesFilesItDoesNotRecogniseAlone() throws IOException {
    Path notes = dataDir.resolve("chunks/zz/yy/notes.txt");
    Files.createDirectories(notes.getParent());
    Files.writeString(notes, "hello");
    byte[] data = bytes(10, 1);
    Path wrongDirectory = otherDirectoryThan(ID).resolve(ID + "." + sha(data));
    Files.createDirectories(wrongDirectory.getParent());
    Files.write(wrongDirectory, data);

    store = reopen();

    assertThat(notes).exists();
    assertThat(wrongDirectory).exists();
    assertThat(store.chunkCount()).isZero();
  }

  @Test
  void bootServesTheNewerOfTwoCopiesAndKeepsTheOther() throws IOException {
    byte[] older = bytes(10, 6);
    byte[] newer = bytes(10, 7);
    Path olderFile = writeCopy(older, 1_000);
    Path newerFile = writeCopy(newer, 2_000);

    store = reopen();

    assertThat(store.get(ID).bytes()).isEqualTo(newer);
    assertThat(olderFile).exists();
    assertThat(newerFile).exists();
  }

  @Test
  void bootBreaksATimestampTieByTheGreaterFileName() throws IOException {
    byte[] first = bytes(10, 6);
    byte[] second = bytes(10, 7);
    writeCopy(first, 1_000);
    writeCopy(second, 1_000);
    byte[] greaterName = sha(first).compareTo(sha(second)) > 0 ? first : second;

    store = reopen();

    assertThat(store.get(ID).bytes()).isEqualTo(greaterName);
  }

  @Test
  void indexedFileMissingFromDiskIsNotFound() throws IOException {
    put(ID, bytes(10, 1));
    Files.delete(storedFile(ID));

    assertApiError(() -> store.get(ID), ErrorCode.CHUNK_NOT_FOUND);
    assertThat(store.chunkCount()).isZero();
  }

  // Concurrency

  @Test
  void concurrentPutsOfOneChunkLeaveOneValidFile() throws Exception {
    byte[] data = bytes(64_000, 8);

    runTogether(8, () -> put(ID, data));

    assertThat(chunkFiles()).hasSize(1);
    assertThat(store.get(ID).bytes()).isEqualTo(data);
  }

  @Test
  void getDuringPutsReturnsOneWholeVersion() throws Exception {
    byte[] first = bytes(200_000, 9);
    byte[] second = bytes(200_000, 10);
    put(ID, first);

    List<Callable<?>> tasks = new ArrayList<>();
    tasks.add(() -> {
      for (int i = 0; i < 50; i++) {
        put(ID, i % 2 == 0 ? second : first);
      }
      return null;
    });
    for (int reader = 0; reader < 4; reader++) {
      tasks.add(() -> {
        for (int i = 0; i < 50; i++) {
          assertThat(store.get(ID).bytes()).isIn(first, second);
        }
        return null;
      });
    }
    runTogether(tasks);
  }

  @Test
  void quarantineRacingAPutNeverQuarantinesTheFreshFile() throws Exception {
    for (int index = 0; index < 20; index++) {
      ChunkId id = chunk(index);
      byte[] data = bytes(50_000, index);
      put(id, data);
      flipFirstByte(storedFile(id));

      runTogether(
          List.of(
              () -> put(id, data),
              () -> {
                try {
                  return store.get(id);
                } catch (ApiException e) {
                  // Reading first finds the corrupt copy; that is the expected outcome of a race.
                  assertThat(e.code()).isEqualTo(ErrorCode.STORED_CHECKSUM_MISMATCH);
                  return null;
                }
              }));

      assertThat(store.get(id).bytes()).isEqualTo(data);
    }
  }

  // Helpers

  private ChunkStore reopen() {
    return new ChunkStore(new WorkerProperties("worker1", dataDir));
  }

  private ChunkInfo put(ChunkId id, byte[] data) {
    return store.put(id, sha(data), new ByteArrayInputStream(data));
  }

  private static ChunkId chunk(int index) {
    return new ChunkId(OBJECT_ID + "-" + String.format(Locale.ROOT, "%06d", index));
  }

  private static byte[] bytes(int size, long seed) {
    byte[] data = new byte[size];
    new Random(seed).nextBytes(data);
    return data;
  }

  private static String sha(byte[] data) {
    return Checksums.sha256Hex(data);
  }

  private Path directoryOf(ChunkId id) {
    String hash = Checksums.sha256Hex(id.value().getBytes(US_ASCII));
    return dataDir.resolve("chunks").resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4));
  }

  private Path otherDirectoryThan(ChunkId id) {
    Path right = directoryOf(id);
    Path other = dataDir.resolve("chunks/00/00");
    return other.equals(right) ? dataDir.resolve("chunks/ff/ff") : other;
  }

  /** Every regular file under the chunk store. */
  private List<Path> chunkFiles() throws IOException {
    try (Stream<Path> files = Files.walk(dataDir.resolve("chunks"))) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  private Path storedFile(ChunkId id) throws IOException {
    return chunkFiles().stream()
        .filter(file -> file.getFileName().toString().startsWith(id + "."))
        .filter(file -> !file.getFileName().toString().endsWith(".corrupt"))
        .findFirst()
        .orElseThrow();
  }

  /** Writes a servable copy of {@link #ID} with the given modification time, behind the store. */
  private Path writeCopy(byte[] data, long modifiedMillis) throws IOException {
    Path file = directoryOf(ID).resolve(ID + "." + sha(data));
    Files.createDirectories(file.getParent());
    Files.write(file, data);
    Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
    return file;
  }

  private static void flipFirstByte(Path file) throws IOException {
    byte[] data = Files.readAllBytes(file);
    data[0] ^= (byte) 0xff;
    Files.write(file, data);
  }

  private static void assertApiError(ThrowingCallable call, ErrorCode code) {
    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(call)
        .satisfies(e -> assertThat(e.code()).isEqualTo(code));
  }

  private static void runTogether(int copies, Callable<?> task) throws Exception {
    runTogether(Collections.nCopies(copies, task));
  }

  /** Starts every task at the same moment and rethrows the first failure. */
  private static void runTogether(List<Callable<?>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> results = new ArrayList<>();
      for (Callable<?> task : tasks) {
        results.add(
            pool.submit(() -> {
              start.await();
              return task.call();
            }));
      }
      start.countDown();
      for (Future<?> result : results) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
