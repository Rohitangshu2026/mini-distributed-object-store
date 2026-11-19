package io.github.rohitangshu2026.objstore.controller.reconcile;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.api.ChunkInfo;
import io.github.rohitangshu2026.objstore.common.api.ExpectedInventory.ExpectedChunk;
import io.github.rohitangshu2026.objstore.common.api.Inventory;
import io.github.rohitangshu2026.objstore.controller.config.ControllerProperties;
import io.github.rohitangshu2026.objstore.controller.membership.WorkerRegistered;
import io.github.rohitangshu2026.objstore.controller.membership.WorkerStateChanged;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.repair.ReplicaDegraded;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the metadata and one worker's disk back into agreement ({@code docs/architecture.md} §9,
 * invariant I5). The metadata is authoritative; the disk is corrected to it, never the reverse.
 *
 * <p>Only a complete inventory is used: if the inventory cannot be fetched, nothing changes, so a
 * worker is never treated as empty. PENDING rows are ignored because their writes are in flight.
 *
 * <p>The startup gate: until every registered worker has delivered an inventory since the
 * controller started, or {@code STARTUP_GRACE_SECS} have passed, no step that creates a tombstone
 * runs. Early on the metadata may not yet reflect copies that other workers hold, so a copy that
 * looks extra could still be needed.
 *
 * <p>It runs on the single {@code maintenanceScheduler} thread when a worker registers or becomes
 * ALIVE, and every 300 s for each ALIVE worker.
 */
@Component
public class Reconciler {

  private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

  /** The length of an object ID, the prefix of every chunk ID. */
  private static final int OBJECT_ID_LENGTH = 36;

  private final WorkerRepository workers;
  private final ReplicaRepository replicas;
  private final ObjectRepository objects;
  private final TombstoneRepository tombstones;
  private final WorkerClient workerClient;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;
  private final OrphanTracker orphans;
  private final TombstoneJob tombstoneJob;
  private final ThreadPoolTaskScheduler maintenanceScheduler;
  private final Clock clock;
  private final long startedAt;
  private final long startupGraceMillis;

  /** Workers that have delivered a complete inventory since the controller started. */
  private final Set<String> inventoried = new HashSet<>();

  /**
   * Creates the reconciler.
   *
   * @param workers the workers
   * @param replicas the chunk copies
   * @param objects the objects and chunks
   * @param tombstones the pending deletes
   * @param workerClient the client that fetches inventories and reads copies
   * @param transactions the transactions around each change
   * @param events where degraded copies are reported
   * @param orphans decides when an orphan file may go
   * @param tombstoneJob carries out the tombstones a run creates
   * @param maintenanceScheduler the single thread that runs reconciliation
   * @param clock the source of timestamps
   * @param properties the startup grace period
   */
  public Reconciler(
      WorkerRepository workers,
      ReplicaRepository replicas,
      ObjectRepository objects,
      TombstoneRepository tombstones,
      WorkerClient workerClient,
      TransactionTemplate transactions,
      ApplicationEventPublisher events,
      OrphanTracker orphans,
      TombstoneJob tombstoneJob,
      @Qualifier("maintenanceScheduler") ThreadPoolTaskScheduler maintenanceScheduler,
      Clock clock,
      ControllerProperties properties) {
    this.workers = workers;
    this.replicas = replicas;
    this.objects = objects;
    this.tombstones = tombstones;
    this.workerClient = workerClient;
    this.transactions = transactions;
    this.events = events;
    this.orphans = orphans;
    this.tombstoneJob = tombstoneJob;
    this.maintenanceScheduler = maintenanceScheduler;
    this.clock = clock;
    this.startedAt = clock.millis();
    this.startupGraceMillis = properties.startupGrace().toMillis();
  }

  /**
   * Reconciles a worker that has just registered.
   *
   * @param registered the registration
   */
  @EventListener
  public void on(WorkerRegistered registered) {
    schedule(registered.workerId());
  }

  /**
   * Reconciles a worker that has just become ALIVE, for example after being DEAD or after a
   * controller restart.
   *
   * @param change the worker's state change
   */
  @EventListener
  public void on(WorkerStateChanged change) {
    if (change.to() == WorkerState.ALIVE) {
      schedule(change.workerId());
    }
  }

  /** Reconciles every ALIVE worker every 300 s. */
  @Scheduled(fixedDelay = 300_000, initialDelay = 300_000, scheduler = "maintenanceScheduler")
  void scheduledRun() {
    workers.findAll().stream()
        .filter(worker -> worker.state() == WorkerState.ALIVE)
        .forEach(worker -> reconcile(worker.workerId()));
  }

  private void schedule(String workerId) {
    maintenanceScheduler.execute(() -> reconcile(workerId));
  }

  /**
   * Reconciles one worker. Never throws: a failure is logged and the next run tries again.
   *
   * @param workerId the worker's ID
   * @return {@code true} if a complete inventory was compared
   */
  public boolean reconcile(String workerId) {
    try {
      return compare(workerId);
    } catch (RuntimeException e) {
      log.atError()
          .setCause(e)
          .addKeyValue("operation", "reconcile")
          .addKeyValue("worker_id", workerId)
          .log("Reconciliation failed; the next run tries again");
      return false;
    }
  }

  private boolean compare(String workerId) {
    Optional<WorkerRow> worker = workers.find(workerId);
    if (worker.isEmpty()) {
      return false;
    }
    WorkerRef ref = new WorkerRef(workerId, worker.get().url());
    Inventory inventory;
    try {
      inventory = workerClient.inventory(ref);
    } catch (WorkerCallException e) {
      // Never treat a worker as empty because its inventory could not be read.
      log.atWarn()
          .addKeyValue("operation", "reconcile")
          .addKeyValue("worker_id", workerId)
          .log("Inventory not available ({}); skipping reconciliation", e.failure());
      return false;
    }
    inventoried.add(workerId);
    boolean gateOpen = gateOpen();
    Map<String, ChunkInfo> onDisk =
        inventory.chunks().stream()
            .collect(Collectors.toMap(ChunkInfo::chunkId, Function.identity(), (a, b) -> a));
    Map<String, Integer> actions = new TreeMap<>();

    List<ExpectedChunk> expected = replicas.expectedOn(workerId);
    Set<String> recorded = new HashSet<>();
    for (ExpectedChunk row : expected) {
      recorded.add(row.chunkId());
      String action = compareRow(ref, row, onDisk.get(row.chunkId()), gateOpen);
      if (action != null) {
        actions.merge(action, 1, Integer::sum);
      }
    }

    Set<String> tombstoned =
        tombstones.forWorker(workerId).stream()
            .map(TombstoneRow::chunkId)
            .collect(Collectors.toSet());
    Set<String> orphanFiles = new HashSet<>();
    for (String chunkId : onDisk.keySet()) {
      if (recorded.contains(chunkId) || tombstoned.contains(chunkId)) {
        continue;
      }
      Optional<ObjectRow> object = objectOf(chunkId);
      if (object.isEmpty()) {
        orphanFiles.add(chunkId);
      } else if (object.get().status() != ObjectStatus.UPLOADING && gateOpen) {
        // A copy the metadata does not know about, of an object that does exist.
        tombstone(chunkId, workerId, TombstoneReason.UNTRACKED_COPY);
        actions.merge("untracked_copy_tombstoned", 1, Integer::sum);
      }
    }
    for (String chunkId : inventory.quarantined()) {
      if (!recorded.contains(chunkId)
          && !tombstoned.contains(chunkId)
          && objectOf(chunkId).isEmpty()) {
        orphanFiles.add(chunkId);
      }
    }
    if (gateOpen) {
      for (String chunkId : orphans.observe(workerId, orphanFiles, clock.millis())) {
        tombstone(chunkId, workerId, TombstoneReason.ORPHAN);
        actions.merge("orphan_tombstoned", 1, Integer::sum);
      }
    }

    if (actions.keySet().stream().anyMatch(action -> action.endsWith("tombstoned"))) {
      tombstoneJob.trigger();
    }
    log.atInfo()
        .addKeyValue("operation", "reconcile")
        .addKeyValue("worker_id", workerId)
        .log(
            "Reconciled {} files against {} recorded copies{}: {}",
            onDisk.size(),
            expected.size(),
            gateOpen ? "" : " (startup gate closed: no tombstones yet)",
            actions.isEmpty() ? "in agreement" : actions);
    return true;
  }

  /**
   * Applies one row of the comparison table of {@code docs/architecture.md} §9. PENDING rows are
   * writes in flight and are left alone.
   *
   * @return a short name for what was done, or {@code null} if nothing was
   */
  private String compareRow(WorkerRef worker, ExpectedChunk row, ChunkInfo disk, boolean gateOpen) {
    return switch (ReplicaStatus.valueOf(row.status())) {
      case PENDING -> null;
      case HEALTHY -> compareHealthy(worker, row, disk);
      case LOST -> compareLost(worker, row, disk, gateOpen);
      case CORRUPT -> compareCorrupt(worker, row, gateOpen);
    };
  }

  /** A HEALTHY copy must be on disk with the recorded checksum. */
  private String compareHealthy(WorkerRef worker, ExpectedChunk row, ChunkInfo disk) {
    if (disk == null) {
      degrade(row.chunkId(), worker.workerId(), ReplicaStatus.LOST);
      return "healthy_marked_lost";
    }
    if (!disk.checksumSha256().equals(row.checksumSha256())) {
      degrade(row.chunkId(), worker.workerId(), ReplicaStatus.CORRUPT);
      return "healthy_marked_corrupt";
    }
    return null;
  }

  /**
   * A LOST copy that is back on disk is removed if 2 HEALTHY copies exist elsewhere, and otherwise
   * verified and readmitted. One that is still missing loses its row if it is not needed; otherwise
   * repair rewrites it in place.
   */
  private String compareLost(
      WorkerRef worker, ExpectedChunk row, ChunkInfo disk, boolean gateOpen) {
    String chunkId = row.chunkId();
    String workerId = worker.workerId();
    boolean enoughElsewhere = replicas.healthyOnAliveExcept(chunkId, workerId) >= 2;
    if (disk != null && enoughElsewhere) {
      return gateOpen && replaceIfStillExtra(chunkId, workerId) ? "extra_copy_tombstoned" : null;
    }
    if (disk != null) {
      return readmit(worker, row);
    }
    if (!enoughElsewhere) {
      return null;
    }
    Boolean deleted =
        transactions.execute(status -> {
          if (replicas.healthyOnAliveExcept(chunkId, workerId) < 2) {
            return false;
          }
          replicas.delete(chunkId, workerId);
          return true;
        });
    return Boolean.TRUE.equals(deleted) ? "lost_row_removed" : null;
  }

  /**
   * A CORRUPT copy is removed if 2 HEALTHY copies exist elsewhere; otherwise repair rewrites it.
   */
  private String compareCorrupt(WorkerRef worker, ExpectedChunk row, boolean gateOpen) {
    boolean enoughElsewhere = replicas.healthyOnAliveExcept(row.chunkId(), worker.workerId()) >= 2;
    return enoughElsewhere && gateOpen && replaceIfStillExtra(row.chunkId(), worker.workerId())
        ? "extra_copy_tombstoned"
        : null;
  }

  /**
   * Verifies a LOST copy that is back on disk and still needed: valid becomes HEALTHY, a mismatch
   * CORRUPT. Anything else leaves it for the next run.
   */
  private String readmit(WorkerRef worker, ExpectedChunk row) {
    try {
      byte[] bytes = workerClient.getChunk(worker, row.chunkId());
      if (bytes.length == row.size() && Checksums.sha256Hex(bytes).equals(row.checksumSha256())) {
        long now = clock.millis();
        transactions.executeWithoutResult(
            status -> replicas.markHealthy(row.chunkId(), worker.workerId(), now));
        return "lost_readmitted";
      }
      transactions.executeWithoutResult(
          status -> replicas.markCorrupt(row.chunkId(), worker.workerId()));
      return "lost_marked_corrupt";
    } catch (WorkerCallException e) {
      if (e.failure() == Failure.CORRUPT) {
        transactions.executeWithoutResult(
            status -> replicas.markCorrupt(row.chunkId(), worker.workerId()));
        return "lost_marked_corrupt";
      }
      return null;
    }
  }

  /**
   * Turns a copy that is not needed into an EXTRA_COPY tombstone, after a re-check in the same
   * transaction.
   */
  private boolean replaceIfStillExtra(String chunkId, String workerId) {
    long now = clock.millis();
    Boolean replaced =
        transactions.execute(status -> {
          if (replicas.healthyOnAliveExcept(chunkId, workerId) < 2) {
            return false;
          }
          replicas.replaceWithTombstone(chunkId, workerId, TombstoneReason.EXTRA_COPY, now);
          return true;
        });
    return Boolean.TRUE.equals(replaced);
  }

  private void degrade(String chunkId, String workerId, ReplicaStatus status) {
    transactions.executeWithoutResult(tx -> {
      if (status == ReplicaStatus.LOST) {
        replicas.markLost(chunkId, workerId);
      } else {
        replicas.markCorrupt(chunkId, workerId);
      }
    });
    log.atWarn()
        .addKeyValue("operation", "reconcile")
        .addKeyValue("chunk_id", chunkId)
        .addKeyValue("worker_id", workerId)
        .log("Inventory disagrees with a HEALTHY copy; marked it {}", status);
    events.publishEvent(new ReplicaDegraded(chunkId, workerId));
  }

  private void tombstone(String chunkId, String workerId, TombstoneReason reason) {
    long now = clock.millis();
    transactions.executeWithoutResult(
        status -> tombstones.insert(new TombstoneRow(chunkId, workerId, reason, now)));
    log.atInfo()
        .addKeyValue("operation", "reconcile")
        .addKeyValue("chunk_id", chunkId)
        .addKeyValue("worker_id", workerId)
        .log("Copy marked for deletion ({})", reason);
  }

  private Optional<ObjectRow> objectOf(String chunkId) {
    return chunkId.length() > OBJECT_ID_LENGTH
        ? objects.find(chunkId.substring(0, OBJECT_ID_LENGTH))
        : Optional.empty();
  }

  /** {@return whether reconciliation may create tombstones yet} */
  private boolean gateOpen() {
    if (clock.millis() - startedAt >= startupGraceMillis) {
      return true;
    }
    return workers.findAll().stream().map(WorkerRow::workerId).allMatch(inventoried::contains);
  }
}
