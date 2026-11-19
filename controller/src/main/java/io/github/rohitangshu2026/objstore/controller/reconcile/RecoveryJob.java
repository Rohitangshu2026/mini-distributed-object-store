package io.github.rohitangshu2026.objstore.controller.reconcile;

import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import io.github.rohitangshu2026.objstore.controller.object.InFlightObjects;
import io.github.rohitangshu2026.objstore.controller.object.ObjectRemoval;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes uploads and deletes that a crash or a failed abort left behind ({@code
 * docs/architecture.md} §9 and §10.4). An UPLOADING object that no request is working on is
 * aborted; a DELETING one has its removal finished. Objects in {@link InFlightObjects} are left
 * alone, so a running upload is never aborted.
 */
@Component
public class RecoveryJob implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;
  private final ObjectRemoval removal;
  private final InFlightObjects inFlight;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the job.
   *
   * @param objects the objects
   * @param replicas the chunk copies, whose interrupted repairs are cleaned up at startup
   * @param removal removes abandoned objects
   * @param inFlight the objects requests are working on
   * @param transactions the transaction for the startup cleanup
   * @param clock the source of timestamps
   */
  public RecoveryJob(
      ObjectRepository objects,
      ReplicaRepository replicas,
      ObjectRemoval removal,
      InFlightObjects inFlight,
      TransactionTemplate transactions,
      Clock clock) {
    this.objects = objects;
    this.replicas = replicas;
    this.removal = removal;
    this.inFlight = inFlight;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Runs {@link #startUp()} before any request is served or any repair starts. */
  @Override
  public void afterSingletonsInstantiated() {
    startUp();
  }

  /**
   * Turns every PENDING copy of a committed object into a {@code REPAIR_INTERRUPTED} tombstone.
   * Such a row belongs to a repair, and no repair survives a restart; the copy, if it was written,
   * is adopted or deleted through its tombstone, and repair places the chunk again.
   */
  public void startUp() {
    long now = clock.millis();
    Integer interrupted =
        transactions.execute(status -> replicas.replaceInterruptedRepairsWithTombstones(now));
    if (interrupted != null && interrupted > 0) {
      log.atInfo()
          .addKeyValue("operation", "recovery")
          .log("Turned {} copies of interrupted repairs into tombstones", interrupted);
    }
  }

  /** Runs recovery at startup and then every 10 s on the {@code maintenanceScheduler} thread. */
  @Scheduled(fixedDelay = 10_000, scheduler = "maintenanceScheduler")
  void scheduledRun() {
    runOnce();
  }

  /** Recovers every abandoned object once. A failure on one object is logged and left for later. */
  public void runOnce() {
    for (String objectId : objects.idsWithStatus(ObjectStatus.UPLOADING)) {
      if (!inFlight.contains(objectId)) {
        recover(
            objectId,
            "abandoned upload",
            () -> removal.remove(objectId, ObjectStatus.UPLOADING, TombstoneReason.UPLOAD_ABORTED));
      }
    }
    for (String objectId : objects.idsWithStatus(ObjectStatus.DELETING)) {
      if (!inFlight.contains(objectId)) {
        recover(
            objectId,
            "unfinished delete",
            () -> {
              removal.attemptDeletes(objectId);
              removal.finish(objectId, TombstoneReason.OBJECT_DELETED);
            });
      }
    }
  }

  private static void recover(String objectId, String what, Runnable step) {
    log.atInfo()
        .addKeyValue("operation", "recovery")
        .addKeyValue("object_id", objectId)
        .log("Recovering an {}", what);
    try {
      step.run();
    } catch (RuntimeException e) {
      log.atError()
          .setCause(e)
          .addKeyValue("operation", "recovery")
          .addKeyValue("object_id", objectId)
          .log("Recovery of an {} failed; the next run tries again", what);
    }
  }
}
