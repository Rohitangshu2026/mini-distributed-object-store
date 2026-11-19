package io.github.rohitangshu2026.objstore.worker.scrub;

import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds corrupt copies without waiting for a download to read them (rule A6). Every {@code
 * SCRUB_INTERVAL_SECS} it re-hashes each stored chunk in turn; a copy that fails is quarantined and
 * reported in the next heartbeat, so the controller marks it CORRUPT and repair rewrites it.
 *
 * <p>One file at a time, with a short pause between files, so a scrub never starves requests or
 * heartbeats; it runs on its own {@code scrubScheduler} thread.
 */
@Component
public class Scrubber {

  private static final Logger log = LoggerFactory.getLogger(Scrubber.class);

  /** The pause between two files, which keeps the disk available for requests. */
  static final Duration PAUSE = Duration.ofMillis(5);

  private final ChunkStore store;

  /**
   * Creates the scrubber.
   *
   * @param store the chunk store to scrub
   */
  public Scrubber(ChunkStore store) {
    this.store = store;
  }

  /** Scrubs every {@code SCRUB_INTERVAL_SECS} on the {@code scrubScheduler} thread. */
  @Scheduled(
      fixedDelayString = "${objstore.scrub-interval}",
      initialDelayString = "${objstore.scrub-interval}",
      scheduler = "scrubScheduler")
  void scheduledRun() {
    runOnce();
  }

  /**
   * Verifies every stored chunk once. A chunk that cannot be read is logged and skipped, and the
   * pass carries on.
   *
   * @return how many chunks were found corrupt and quarantined
   */
  public int runOnce() {
    int checked = 0;
    int corrupt = 0;
    int unreadable = 0;
    for (ChunkId id : store.chunkIds()) {
      try {
        if (!store.verify(id)) {
          corrupt++;
        }
        checked++;
      } catch (ApiException e) {
        unreadable++;
        log.atError()
            .setCause(e.getCause())
            .addKeyValue("operation", "scrub")
            .addKeyValue("chunk_id", id.value())
            .log("Could not read the chunk to verify it; the next read or scrub tries again");
      }
      try {
        Thread.sleep(PAUSE);
      } catch (InterruptedException e) {
        // Shutting down: stop this pass; the next start scrubs everything again.
        Thread.currentThread().interrupt();
        break;
      }
    }
    (corrupt > 0 || unreadable > 0 ? log.atWarn() : log.atInfo())
        .addKeyValue("operation", "scrub")
        .log(
            "Scrub pass: {} chunks verified, {} corrupt and quarantined, {} unreadable",
            checked,
            corrupt,
            unreadable);
    return corrupt;
  }
}
