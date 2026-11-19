package io.github.rohitangshu2026.objstore.controller.object;

import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes an object in three steps that each leave a state recovery can finish ({@code
 * docs/architecture.md} §9): record the intent, delete the copies it can reach, then drop the
 * object's rows.
 *
 * <ol>
 *   <li>{@link #begin}: one transaction moves the object to DELETING and turns every copy row into
 *       a tombstone, so no copy is ever forgotten (rule N4).
 *   <li>{@link #attemptDeletes}: deletes each copy on an ALIVE worker and removes its tombstone
 *       once the worker confirms. Copies it cannot reach keep their tombstones for the tombstone
 *       job.
 *   <li>{@link #finish}: deletes the object row; its chunk rows go with it.
 * </ol>
 */
@Component
public class ObjectRemoval {

  private static final Logger log = LoggerFactory.getLogger(ObjectRemoval.class);

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;
  private final TombstoneRepository tombstones;
  private final WorkerRepository workers;
  private final WorkerClient workerClient;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the remover.
   *
   * @param objects the objects
   * @param replicas the chunk copies
   * @param tombstones the pending deletes
   * @param workers the workers, to find the ALIVE ones
   * @param workerClient the client that deletes copies
   * @param transactions the transactions for each step
   * @param clock the source of timestamps
   */
  public ObjectRemoval(
      ObjectRepository objects,
      ReplicaRepository replicas,
      TombstoneRepository tombstones,
      WorkerRepository workers,
      WorkerClient workerClient,
      TransactionTemplate transactions,
      Clock clock) {
    this.objects = objects;
    this.replicas = replicas;
    this.tombstones = tombstones;
    this.workers = workers;
    this.workerClient = workerClient;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Removes an object that has the expected status: all three steps.
   *
   * @param objectId the object's ID
   * @param from the status the object must have
   * @param reason why its copies must go
   * @return {@code false} if the object did not have status {@code from}, and nothing was changed
   */
  public boolean remove(String objectId, ObjectStatus from, TombstoneReason reason) {
    if (!begin(objectId, from, reason)) {
      return false;
    }
    attemptDeletes(objectId);
    finish(objectId, reason);
    return true;
  }

  /**
   * Moves the object to DELETING and turns every copy row into a tombstone, in one transaction.
   *
   * @param objectId the object's ID
   * @param from the status the object must have
   * @param reason why its copies must go
   * @return {@code false} if the object did not have status {@code from}, and nothing was changed
   */
  public boolean begin(String objectId, ObjectStatus from, TombstoneReason reason) {
    long now = clock.millis();
    Boolean begun =
        transactions.execute(status -> {
          if (!objects.markDeleting(objectId, from, now)) {
            return false;
          }
          int copies = replicas.replaceAllWithTombstones(objectId, reason, now);
          log.atInfo()
              .addKeyValue("operation", "remove")
              .addKeyValue("object_id", objectId)
              .log("Deleting object ({}); {} copies to delete", reason, copies);
          return true;
        });
    return Boolean.TRUE.equals(begun);
  }

  /**
   * Deletes the object's copies on ALIVE workers and removes each tombstone once its worker
   * confirms. A copy whose worker is not ALIVE or does not answer keeps its tombstone.
   *
   * @param objectId the object's ID
   * @return how many copies were confirmed deleted
   */
  public int attemptDeletes(String objectId) {
    Map<String, WorkerRow> alive =
        workers.findAll().stream()
            .filter(worker -> worker.state() == WorkerState.ALIVE)
            .collect(Collectors.toMap(WorkerRow::workerId, Function.identity()));
    int deleted = 0;
    for (TombstoneRow tombstone : tombstones.forObject(objectId)) {
      WorkerRow worker = alive.get(tombstone.workerId());
      if (worker == null) {
        continue;
      }
      try {
        workerClient.deleteChunk(
            new WorkerRef(worker.workerId(), worker.url()), tombstone.chunkId());
      } catch (WorkerCallException e) {
        if (e.failure() != Failure.NOT_FOUND) {
          log.atWarn()
              .addKeyValue("operation", "remove")
              .addKeyValue("object_id", objectId)
              .addKeyValue("chunk_id", tombstone.chunkId())
              .addKeyValue("worker_id", tombstone.workerId())
              .log("Could not delete a copy ({}); its tombstone stays", e.failure());
          continue;
        }
      }
      transactions.executeWithoutResult(
          status -> tombstones.delete(tombstone.chunkId(), tombstone.workerId()));
      deleted++;
    }
    return deleted;
  }

  /**
   * Drops the object's rows. Any copy row still left becomes a tombstone first, in the same
   * transaction, because deleting the chunk rows would otherwise take the copy rows with them.
   *
   * @param objectId the object's ID
   * @param reason why any remaining copies must go
   */
  public void finish(String objectId, TombstoneReason reason) {
    long now = clock.millis();
    transactions.executeWithoutResult(status -> {
      replicas.replaceAllWithTombstones(objectId, reason, now);
      objects.delete(objectId);
    });
    log.atInfo()
        .addKeyValue("operation", "remove")
        .addKeyValue("object_id", objectId)
        .log("Object removed ({})", reason);
  }
}
