package io.github.rohitangshu2026.objstore.worker.scrub;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests a scrub pass against a real {@link ChunkStore} on a temp directory. */
class ScrubberTest {

  private static final String OBJECT_ID = "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f";

  @TempDir Path dataDir;

  private ChunkStore store;
  private Scrubber scrubber;

  @BeforeEach
  void setUp() {
    store =
        new ChunkStore(
            new WorkerProperties(
                "worker1",
                dataDir,
                URI.create("http://controller:8080"),
                "http://worker1:9000",
                Duration.ofSeconds(3)));
    scrubber = new Scrubber(store);
  }

  @Test
  void passQuarantinesAndReportsOnlyTheCorruptChunk() throws IOException {
    for (int i = 0; i < 3; i++) {
      put(chunk(i), bytes(2000, i));
    }
    flipFirstByte(file(chunk(1)));

    assertThat(scrubber.runOnce()).isEqualTo(1);

    assertThat(store.chunkIds()).containsExactly(chunk(0), chunk(2));
    assertThat(store.quarantined()).containsExactly(chunk(1).value());
    assertThat(store.corruptToReport()).containsExactly(chunk(1).value());
    assertThat(scrubber.runOnce()).as("a second pass finds nothing new").isZero();
  }

  @Test
  void unreadableChunkIsLoggedAndThePassCarriesOn() throws IOException {
    put(chunk(0), bytes(2000, 0));
    put(chunk(1), bytes(2000, 1));
    put(chunk(2), bytes(2000, 2));
    // A directory where the file was: reading it fails, as a disk error would, even as root.
    Path unreadable = file(chunk(0));
    Files.delete(unreadable);
    Files.createDirectory(unreadable);
    flipFirstByte(file(chunk(2)));

    assertThat(scrubber.runOnce()).isEqualTo(1);
    assertThat(store.quarantined()).containsExactly(chunk(2).value());
  }

  @Test
  void scrubRacingAPutNeverQuarantinesTheFreshCopy() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 20; round++) {
        ChunkId id = chunk(round);
        byte[] data = bytes(50_000, round);
        put(id, data);
        flipFirstByte(file(id));

        Future<?> rewrite = pool.submit(() -> put(id, data));
        Future<Integer> scrub = pool.submit(scrubber::runOnce);
        rewrite.get();
        scrub.get();

        // Whichever ran first, the fresh copy is servable and verifies.
        assertThat(store.get(id).bytes()).isEqualTo(data);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private void put(ChunkId id, byte[] data) {
    store.put(id, Checksums.sha256Hex(data), new ByteArrayInputStream(data));
  }

  private Path file(ChunkId id) throws IOException {
    try (Stream<Path> files = Files.walk(dataDir.resolve("chunks"))) {
      return files
          .filter(f -> f.getFileName().toString().startsWith(id.value() + "."))
          .filter(f -> !f.getFileName().toString().endsWith(".corrupt"))
          .findFirst()
          .orElseThrow();
    }
  }

  private static ChunkId chunk(int index) {
    return ChunkId.of(OBJECT_ID, index);
  }

  private static byte[] bytes(int size, long seed) {
    byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

  private static void flipFirstByte(Path file) throws IOException {
    byte[] data = Files.readAllBytes(file);
    data[0] ^= 1;
    Files.write(file, data);
  }
}
