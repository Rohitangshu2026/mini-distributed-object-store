package io.github.rohitangshu2026.objstore.controller.object;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
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
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.placement.Placement;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores an uploaded object as chunks, each with two verified copies on different workers ({@code
 * docs/architecture.md} §9, steps U1 to U7).
 *
 * <p>Every write is preceded by a committed PENDING row (rule N4) and becomes HEALTHY only after
 * its worker confirms a verified write (rule N2). A copy that fails, or whose outcome is unknown,
 * becomes a {@code WRITE_FAILED} tombstone and is written to the next eligible worker instead. Any
 * failure that ends the upload aborts it, so it never leaves an object that looks complete.
 *
 * <p>Memory stays at one chunk per upload (rule A4): the staged file is read into one reused {@code
 * CHUNK_SIZE} buffer, which is refilled only after both writes of the previous chunk have finished.
 */
@Service
public class UploadService {

  private static final Logger log = LoggerFactory.getLogger(UploadService.class);
  private static final int COPIES = 2;

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;
  private final WorkerRepository workers;
  private final ObjectRemoval removal;
  private final InFlightObjects inFlight;
  private final WorkerClient workerClient;
  private final AsyncTaskExecutor replicaWrites;
  private final TransactionTemplate transactions;
  private final UuidV7 objectIds;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param objects the objects and chunks
   * @param replicas the chunk copies
   * @param workers the workers, to find the ALIVE ones
   * @param removal aborts a failed upload
   * @param inFlight the objects requests are working on, which recovery leaves alone
   * @param workerClient the client that writes copies
   * @param replicaWrites the pool that writes the second copy of each chunk
   * @param transactions the transactions around each metadata change
   * @param objectIds the generator of object IDs
   * @param clock the source of timestamps
   */
  public UploadService(
      ObjectRepository objects,
      ReplicaRepository replicas,
      WorkerRepository workers,
      ObjectRemoval removal,
      InFlightObjects inFlight,
      WorkerClient workerClient,
      @Qualifier("replicaWriteExecutor") AsyncTaskExecutor replicaWrites,
      TransactionTemplate transactions,
      UuidV7 objectIds,
      Clock clock) {
    this.objects = objects;
    this.replicas = replicas;
    this.workers = workers;
    this.removal = removal;
    this.inFlight = inFlight;
    this.workerClient = workerClient;
    this.replicaWrites = replicaWrites;
    this.transactions = transactions;
    this.objectIds = objectIds;
    this.clock = clock;
  }

  /**
   * Returns the workers an upload may use, checked before the request body is read (U1).
   *
   * @return the ALIVE workers, sorted by worker ID
   * @throws ApiException {@link ErrorCode#INSUFFICIENT_WORKERS} if fewer than 2 are ALIVE
   */
  public List<WorkerRef> aliveWorkers() {
    List<WorkerRef> alive =
        workers.findAll().stream()
            .filter(worker -> worker.state() == WorkerState.ALIVE)
            .map(worker -> new WorkerRef(worker.workerId(), worker.url()))
            .toList();
    if (alive.size() < COPIES) {
      throw new ApiException(
          ErrorCode.INSUFFICIENT_WORKERS,
          "At least 2 workers must be ALIVE to store an object; ALIVE now: " + alive.size());
    }
    return alive;
  }

  /**
   * Stores an object (U2 to U7).
   *
   * @param alive the workers from {@link #aliveWorkers()}
   * @param name the object's name
   * @param contentType the object's content type
   * @param customMetadata the validated custom metadata, as stored JSON
   * @param content the object's bytes
   * @return the committed object
   * @throws ApiException {@link ErrorCode#INSUFFICIENT_WORKERS} if a chunk ran out of workers to
   *     hold its copies; the upload has been aborted
   * @throws UncheckedIOException if the staged upload could not be read; the upload has been
   *     aborted
   */
  public ObjectRow upload(
      List<WorkerRef> alive,
      String name,
      String contentType,
      String customMetadata,
      InputStream content) {
    String objectId = objectIds.next().toString();
    // Before the first transaction, so recovery never sees this upload as abandoned.
    inFlight.add(objectId);
    try {
      long now = clock.millis();
      transactions.executeWithoutResult(status ->
          objects.insert(
              new ObjectRow(
                  objectId,
                  name == null ? objectId : name,
                  null,
                  contentType,
                  null,
                  null,
                  ObjectStatus.UPLOADING,
                  now,
                  now,
                  customMetadata)));
      try {
        storeAndCommit(objectId, alive, content);
      } catch (RuntimeException e) {
        abort(objectId, e);
        throw e;
      }
      return objects.find(objectId).orElseThrow();
    } finally {
      inFlight.remove(objectId);
    }
  }

  /** Reads the content chunk by chunk, stores each chunk's copies, then commits the object. */
  private void storeAndCommit(String objectId, List<WorkerRef> alive, InputStream content) {
    Map<String, WorkerRef> byId =
        alive.stream().collect(Collectors.toMap(WorkerRef::workerId, Function.identity()));
    List<String> workerIds = alive.stream().map(WorkerRef::workerId).toList();
    // One load snapshot per upload, updated as copies are placed, so placement needs no query.
    Map<String, Long> load = new HashMap<>(replicas.rowCountByWorker());
    MessageDigest wholeObject = Checksums.newSha256();
    byte[] buffer = new byte[CHUNK_SIZE];
    long size = 0;
    int index = 0;
    while (true) {
      int length = readChunk(content, buffer);
      if (length == 0) {
        break;
      }
      wholeObject.update(buffer, 0, length);
      storeChunk(objectId, index, buffer, length, workerIds, byId, load);
      size += length;
      index++;
      if (length < CHUNK_SIZE) {
        break;
      }
    }
    long committedSize = size;
    int chunkCount = index;
    String checksum = Checksums.toHex(wholeObject.digest());
    transactions.executeWithoutResult(
        status -> objects.commit(objectId, committedSize, checksum, chunkCount, clock.millis()));
    log.atInfo()
        .addKeyValue("operation", "upload")
        .addKeyValue("object_id", objectId)
        .log("Object committed: {} bytes in {} chunks", committedSize, chunkCount);
  }

  /** Stores one chunk's two copies (U3 to U6). */
  private void storeChunk(
      String objectId,
      int index,
      byte[] buffer,
      int length,
      List<String> workerIds,
      Map<String, WorkerRef> byId,
      Map<String, Long> load) {
    String chunkId = ChunkId.of(objectId, index).value();
    MessageDigest digest = Checksums.newSha256();
    digest.update(buffer, 0, length);
    String checksum = Checksums.toHex(digest.digest());

    // Every worker that has held a row for this chunk; none may be chosen again (rule I4).
    Set<String> used = new HashSet<>();
    List<String> targets = Placement.choose(workerIds, load, index, used, COPIES);
    if (targets.size() < COPIES) {
      throw insufficientWorkers(chunkId);
    }
    targets.forEach(worker -> place(worker, used, load));
    transactions.executeWithoutResult(status -> {
      objects.insertChunk(new ChunkRow(chunkId, objectId, index, length, checksum));
      targets.forEach(
          worker -> replicas.insert(new ReplicaRow(chunkId, worker, ReplicaStatus.PENDING, null)));
    });

    // The second copy is written in parallel; the buffer is reused only after both finish.
    String first = targets.get(0);
    String second = targets.get(1);
    Future<Boolean> secondWrite =
        replicaWrites.submit(() -> write(byId.get(second), chunkId, buffer, length, checksum));
    boolean firstWritten = write(byId.get(first), chunkId, buffer, length, checksum);
    boolean secondWritten = await(secondWrite);

    for (String worker : List.of(first, second)) {
      boolean written = worker.equals(first) ? firstWritten : secondWritten;
      String holder =
          written
              ? worker
              : replace(
                  chunkId, worker, index, buffer, length, checksum, workerIds, byId, load, used);
      long verifiedAt = clock.millis();
      transactions.executeWithoutResult(
          status -> replicas.markHealthy(chunkId, holder, verifiedAt));
    }
  }

  /**
   * Replaces a failed copy: its row becomes a {@code WRITE_FAILED} tombstone and a new PENDING row
   * goes to the next eligible worker, which is then written. Repeats until a write succeeds.
   *
   * @return the worker that now holds the copy
   * @throws ApiException {@link ErrorCode#INSUFFICIENT_WORKERS} if no eligible worker is left
   */
  private String replace(
      String chunkId,
      String failed,
      int index,
      byte[] buffer,
      int length,
      String checksum,
      List<String> workerIds,
      Map<String, WorkerRef> byId,
      Map<String, Long> load,
      Set<String> used) {
    String current = failed;
    while (true) {
      List<String> next = Placement.choose(workerIds, load, index, used, 1);
      if (next.isEmpty()) {
        throw insufficientWorkers(chunkId);
      }
      String replacement = next.get(0);
      place(replacement, used, load);
      String previous = current;
      long now = clock.millis();
      transactions.executeWithoutResult(status -> {
        replicas.replaceWithTombstone(chunkId, previous, TombstoneReason.WRITE_FAILED, now);
        replicas.insert(new ReplicaRow(chunkId, replacement, ReplicaStatus.PENDING, null));
      });
      if (write(byId.get(replacement), chunkId, buffer, length, checksum)) {
        return replacement;
      }
      current = replacement;
    }
  }

  /** Writes one copy; a failure of any kind is logged and reported as {@code false}. */
  private boolean write(
      WorkerRef worker, String chunkId, byte[] buffer, int length, String checksum) {
    try {
      workerClient.putChunk(worker, chunkId, buffer, length, checksum);
      return true;
    } catch (WorkerCallException e) {
      log.atWarn()
          .addKeyValue("operation", "upload")
          .addKeyValue("chunk_id", chunkId)
          .addKeyValue("worker_id", worker.workerId())
          .log("Copy not confirmed ({}); placing it on another worker", e.failure());
      return false;
    }
  }

  private static boolean await(Future<Boolean> write) {
    try {
      return write.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while writing a copy", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Copy write failed unexpectedly", e.getCause());
    }
  }

  private static void place(String worker, Set<String> used, Map<String, Long> load) {
    used.add(worker);
    load.merge(worker, 1L, Long::sum);
  }

  private static int readChunk(InputStream content, byte[] buffer) {
    try {
      // readNBytes fills the buffer unless the stream ends, unlike read.
      return content.readNBytes(buffer, 0, buffer.length);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the staged upload", e);
    }
  }

  private static ApiException insufficientWorkers(String chunkId) {
    return new ApiException(
        ErrorCode.INSUFFICIENT_WORKERS,
        "No eligible worker left for a copy of chunk " + chunkId + "; nothing was stored");
  }

  /** Aborts a failed upload. If the abort fails too, recovery finishes it. */
  private void abort(String objectId, RuntimeException cause) {
    try {
      removal.remove(objectId, ObjectStatus.UPLOADING, TombstoneReason.UPLOAD_ABORTED);
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
      log.atError()
          .setCause(e)
          .addKeyValue("operation", "upload")
          .addKeyValue("object_id", objectId)
          .log("Could not abort the failed upload; recovery will finish it");
    }
  }
}
