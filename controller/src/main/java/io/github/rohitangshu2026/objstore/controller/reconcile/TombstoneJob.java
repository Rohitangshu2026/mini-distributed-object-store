package io.github.rohitangshu2026.objstore.controller.reconcile;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.controller.membership.WorkerStateChanged;
import io.github.rohitangshu2026.objstore.controller.metadata.ChunkRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carries out tombstones, the only route by which a copy is ever deleted ({@code
 * docs/architecture.md} §8, rule N5).
 *
 * <p>Only tombstones on ALIVE workers are processed. The tombstone's object decides what happens:
 *
 * <ul>
 *   <li>being deleted or gone: send DELETE, and remove the tombstone once the worker confirms;
 *   <li>still uploading: wait until it commits or is aborted;
 *   <li>committed, with at least 2 HEALTHY copies on other ALIVE workers, checked immediately
 *       before the DELETE: delete the copy;
 *   <li>committed with fewer: delete nothing; read and verify the copy, and adopt it as HEALTHY if
 *       it is valid, so a copy that is still needed is never thrown away.
 * </ul>
 *
 * <p>It runs every 10 s on the single {@code maintenanceScheduler} thread, and as soon as a worker
 * becomes ALIVE.
 */
@Component
public class TombstoneJob {

  private static final Logger log = LoggerFactory.getLogger(TombstoneJob.class);

  /** The length of an object ID, the prefix of every chunk ID. */
  private static final int OBJECT_ID_LENGTH = 36;

  /** What happened to one tombstone. */
  enum Result {
    /** The worker confirmed the delete and the tombstone was removed. */
    DELETED,
    /** The copy was still needed and valid, so it became a HEALTHY copy again. */
    ADOPTED,
    /** The copy was already gone or quarantined, so the tombstone was removed. */
    DROPPED,
    /** The object is still uploading. */
    WAITING,
    /** The worker did not answer; the next run tries again. */
    KEPT
  }

  private final TombstoneRepository tombstones;
  private final ReplicaRepository replicas;
  private final ObjectRepository objects;
  private final WorkerRepository workers;
  private final WorkerClient workerClient;
  private final TransactionTemplate transactions;
  private final ThreadPoolTaskScheduler maintenanceScheduler;
  private final Clock clock;
  private final AtomicBoolean triggered = new AtomicBoolean();

  /**
   * Creates the job.
   *
   * @param tombstones the tombstones
   * @param replicas the chunk copies, for the N5 check and adoption
   * @param objects the objects and chunks, which decide each tombstone's fate
   * @param workers the workers, to find the ALIVE ones
   * @param workerClient the client that deletes and reads copies
   * @param transactions the transactions around each change
   * @param maintenanceScheduler the single thread that runs the job
   * @param clock the source of timestamps
   */
  public TombstoneJob(
      TombstoneRepository tombstones,
      ReplicaRepository replicas,
      ObjectRepository objects,
      WorkerRepository workers,
      WorkerClient workerClient,
      TransactionTemplate transactions,
      @Qualifier("maintenanceScheduler") ThreadPoolTaskScheduler maintenanceScheduler,
      Clock clock) {
    this.tombstones = tombstones;
    this.replicas = replicas;
    this.objects = objects;
    this.workers = workers;
    this.workerClient = workerClient;
    this.transactions = transactions;
    this.maintenanceScheduler = maintenanceScheduler;
    this.clock = clock;
  }

  /** Runs every 10 s on the {@code maintenanceScheduler} thread. */
  @Scheduled(fixedDelay = 10_000, initialDelay = 10_000, scheduler = "maintenanceScheduler")
  void scheduledRun() {
    runOnce();
  }

  /**
   * Runs as soon as a worker becomes ALIVE, so its pending deletes do not wait.
   *
   * @param change the worker's state change
   */
  @EventListener
  public void on(WorkerStateChanged change) {
    if (change.to() == WorkerState.ALIVE) {
      trigger();
    }
  }

  /** Schedules one extra run now; triggers that arrive before it starts share it. */
  public void trigger() {
    if (triggered.compareAndSet(false, true)) {
      maintenanceScheduler.execute(() -> {
        triggered.set(false);
        runOnce();
      });
    }
  }

  /**
   * Processes every tombstone on an ALIVE worker once. A failure on one is logged and left for the
   * next run.
   *
   * @return how many tombstones ended with each result
   */
  public Map<Result, Integer> runOnce() {
    Map<Result, Integer> results = new EnumMap<>(Result.class);
    for (WorkerRow worker : workers.findAll()) {
      if (worker.state() != WorkerState.ALIVE) {
        continue;
      }
      WorkerRef ref = new WorkerRef(worker.workerId(), worker.url());
      for (TombstoneRow tombstone : tombstones.forWorker(worker.workerId())) {
        try {
          results.merge(process(tombstone, ref), 1, Integer::sum);
        } catch (RuntimeException e) {
          log.atError()
              .setCause(e)
              .addKeyValue("operation", "tombstone")
              .addKeyValue("chunk_id", tombstone.chunkId())
              .addKeyValue("worker_id", tombstone.workerId())
              .log("Could not process the tombstone; the next run tries again");
        }
      }
    }
    if (results.keySet().stream().anyMatch(result -> result != Result.WAITING)) {
      log.atInfo().addKeyValue("operation", "tombstone").log("Tombstone run: {}", results);
    }
    return results;
  }

  private Result process(TombstoneRow tombstone, WorkerRef worker) {
    String chunkId = tombstone.chunkId();
    Optional<ObjectRow> object =
        chunkId.length() > OBJECT_ID_LENGTH
            ? objects.find(chunkId.substring(0, OBJECT_ID_LENGTH))
            : Optional.empty();
    if (object.isEmpty() || object.get().status() == ObjectStatus.DELETING) {
      return delete(tombstone, worker);
    }
    if (object.get().status() == ObjectStatus.UPLOADING) {
      return Result.WAITING;
    }
    Optional<ChunkRow> chunk = objects.findChunk(chunkId);
    if (chunk.isEmpty()) {
      return delete(tombstone, worker);
    }
    // N5: checked in a transaction immediately before the DELETE is sent.
    Integer remaining =
        transactions.execute(status -> replicas.healthyOnAliveExcept(chunkId, worker.workerId()));
    if (remaining != null && remaining >= 2) {
      return delete(tombstone, worker);
    }
    return adoptIfValid(tombstone, worker, chunk.get());
  }

  /** Sends DELETE; a worker that has no copy counts as a confirmed delete. */
  private Result delete(TombstoneRow tombstone, WorkerRef worker) {
    try {
      workerClient.deleteChunk(worker, tombstone.chunkId());
    } catch (WorkerCallException e) {
      if (e.failure() != Failure.NOT_FOUND) {
        log.atWarn()
            .addKeyValue("operation", "tombstone")
            .addKeyValue("chunk_id", tombstone.chunkId())
            .addKeyValue("worker_id", worker.workerId())
            .log("Delete not confirmed ({}); the tombstone stays", e.failure());
        return Result.KEPT;
      }
    }
    transactions.executeWithoutResult(
        status -> tombstones.delete(tombstone.chunkId(), tombstone.workerId()));
    log.atInfo()
        .addKeyValue("operation", "tombstone")
        .addKeyValue("chunk_id", tombstone.chunkId())
        .addKeyValue("worker_id", worker.workerId())
        .log("Copy deleted ({})", tombstone.reason());
    return Result.DELETED;
  }

  /**
   * Deletes nothing: the copy may be one of the last. Reads it, and adopts it as a HEALTHY copy if
   * it matches the metadata.
   */
  private Result adoptIfValid(TombstoneRow tombstone, WorkerRef worker, ChunkRow chunk) {
    byte[] bytes;
    try {
      bytes = workerClient.getChunk(worker, chunk.chunkId());
    } catch (WorkerCallException e) {
      if (e.failure() == Failure.CORRUPT || e.failure() == Failure.NOT_FOUND) {
        // Quarantined or gone: there is no copy left to delete or adopt.
        transactions.executeWithoutResult(
            status -> tombstones.delete(tombstone.chunkId(), tombstone.workerId()));
        return Result.DROPPED;
      }
      return Result.KEPT;
    }
    if (bytes.length != chunk.size()
        || !Checksums.sha256Hex(bytes).equals(chunk.checksumSha256())) {
      // Not a valid copy, so deleting it cannot lower the number of good copies.
      return delete(tombstone, worker);
    }
    long now = clock.millis();
    transactions.executeWithoutResult(status -> {
      tombstones.delete(tombstone.chunkId(), tombstone.workerId());
      replicas.insert(
          new ReplicaRow(chunk.chunkId(), worker.workerId(), ReplicaStatus.HEALTHY, now));
    });
    log.atInfo()
        .addKeyValue("operation", "tombstone")
        .addKeyValue("chunk_id", chunk.chunkId())
        .addKeyValue("worker_id", worker.workerId())
        .log("Copy still needed and valid; adopted it as HEALTHY instead of deleting it");
    return Result.ADOPTED;
  }
}
