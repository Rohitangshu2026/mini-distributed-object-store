package io.github.rohitangshu2026.objstore.controller.reconcile;

import io.github.rohitangshu2026.objstore.controller.config.ControllerProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides when a file whose object does not exist may be deleted (invariant I5). A file is deleted
 * only if complete inventories keep showing it for at least {@code GC_GRACE_SECS}, so a file that
 * belongs to an operation still in progress is never mistaken for an orphan. With {@code
 * GC_DRY_RUN} it only logs what it would delete.
 *
 * <p>Sightings live in memory: a controller restart only delays cleanup. Used only from the {@code
 * maintenanceScheduler} thread, so it needs no locking.
 */
@Component
public class OrphanTracker {

  private static final Logger log = LoggerFactory.getLogger(OrphanTracker.class);

  private final long graceMillis;
  private final boolean dryRun;

  /** For each worker, each orphan chunk it showed and when it was first seen. */
  private final Map<String, Map<String, Long>> firstSeen = new HashMap<>();

  /**
   * Creates the tracker.
   *
   * @param properties the grace period and the dry-run switch
   */
  public OrphanTracker(ControllerProperties properties) {
    this.graceMillis = properties.gcGrace().toMillis();
    this.dryRun = properties.gcDryRun();
  }

  /**
   * Records the orphans a complete inventory showed and returns those due for deletion. A file the
   * inventory no longer shows loses its sighting.
   *
   * @param workerId the worker the inventory came from
   * @param orphans the chunk IDs of its files whose object does not exist
   * @param now the time of the inventory, in epoch milliseconds
   * @return the chunk IDs seen for at least the grace period, which now get tombstones; empty in a
   *     dry run
   */
  public List<String> observe(String workerId, Set<String> orphans, long now) {
    Map<String, Long> seen = firstSeen.computeIfAbsent(workerId, id -> new HashMap<>());
    seen.keySet().retainAll(orphans);
    List<String> due = new ArrayList<>();
    for (String chunkId : orphans) {
      Long first = seen.putIfAbsent(chunkId, now);
      if (first != null && now - first >= graceMillis) {
        if (dryRun) {
          log.atInfo()
              .addKeyValue("operation", "orphan_cleanup")
              .addKeyValue("chunk_id", chunkId)
              .addKeyValue("worker_id", workerId)
              .log("Dry run: would delete this orphan file");
        } else {
          due.add(chunkId);
          seen.remove(chunkId);
        }
      }
    }
    return due;
  }
}
