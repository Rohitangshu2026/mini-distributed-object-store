package io.github.rohitangshu2026.objstore.controller.object;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.metadata.ChunkRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaLocation;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serves an object's bytes, never sending a byte that has not matched its checksum (rule N3, {@code
 * docs/architecture.md} §9).
 *
 * <p>{@link #prepare} does every check that can still become an error status. {@link #write} then
 * reads the chunks in order, one at a time: each chunk is fetched whole, checked against the
 * metadata, and only then written. A copy that is corrupt is marked CORRUPT and one that is missing
 * is marked LOST; a copy that cannot be reached is skipped with no change, because liveness belongs
 * to membership. The whole-object checksum is compared before the last chunk is sent. A failure
 * after the headers have gone out can only abort the response, which the client sees as a body
 * shorter than {@code Content-Length}.
 */
@Service
public class DownloadService {

  private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

  /** ALIVE workers first, then SUSPECT ones; by worker ID within each. */
  private static final Comparator<ReplicaLocation> PREFERRED_FIRST =
      Comparator.comparing((ReplicaLocation copy) -> copy.workerState() != WorkerState.ALIVE)
          .thenComparing(ReplicaLocation::workerId);

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;
  private final WorkerClient workerClient;
  private final TransactionTemplate transactions;

  /**
   * A download that has passed every check that can still be answered with an error status.
   *
   * @param object the committed object
   * @param chunks its chunks in order, each with the copies to try, best first
   */
  public record Download(ObjectRow object, List<PlannedChunk> chunks) {}

  /**
   * One chunk of a download.
   *
   * @param chunk the chunk
   * @param copies its HEALTHY copies on ALIVE or SUSPECT workers, ALIVE first
   */
  public record PlannedChunk(ChunkRow chunk, List<ReplicaLocation> copies) {}

  /**
   * Creates the service.
   *
   * @param objects the objects and chunks
   * @param replicas the chunk copies, marked CORRUPT or LOST when a read finds them so
   * @param workerClient the client that reads copies
   * @param transactions the transactions around each mark
   */
  public DownloadService(
      ObjectRepository objects,
      ReplicaRepository replicas,
      WorkerClient workerClient,
      TransactionTemplate transactions) {
    this.objects = objects;
    this.replicas = replicas;
    this.workerClient = workerClient;
    this.transactions = transactions;
  }

  /**
   * Checks that an object can be downloaded and plans which copies to read.
   *
   * @param objectId the object's ID
   * @return the planned download
   * @throws ApiException {@link ErrorCode#OBJECT_NOT_FOUND}, {@link
   *     ErrorCode#OBJECT_NOT_COMMITTED}, or {@link ErrorCode#CHUNK_UNAVAILABLE} if some chunk has
   *     no HEALTHY copy on an ALIVE or SUSPECT worker
   */
  public Download prepare(String objectId) {
    ObjectRow object =
        objects
            .find(objectId)
            .orElseThrow(() ->
                new ApiException(ErrorCode.OBJECT_NOT_FOUND, "No object with ID " + objectId));
    if (object.status() != ObjectStatus.COMMITTED) {
      throw new ApiException(
          ErrorCode.OBJECT_NOT_COMMITTED, "Object " + objectId + " is " + object.status());
    }
    Map<String, List<ReplicaLocation>> usable =
        replicas.locationsOf(objectId).stream()
            .filter(copy -> copy.status() == ReplicaStatus.HEALTHY)
            .filter(copy -> copy.workerState() != WorkerState.DEAD)
            .sorted(PREFERRED_FIRST)
            .collect(Collectors.groupingBy(ReplicaLocation::chunkId));
    List<PlannedChunk> chunks =
        objects.chunksOf(objectId).stream()
            .map(chunk -> new PlannedChunk(chunk, usable.getOrDefault(chunk.chunkId(), List.of())))
            .toList();
    for (PlannedChunk planned : chunks) {
      if (planned.copies().isEmpty()) {
        throw new ApiException(
            ErrorCode.CHUNK_UNAVAILABLE,
            "Chunk " + planned.chunk().chunkId() + " has no healthy copy on a live worker");
      }
    }
    return new Download(object, chunks);
  }

  /**
   * Writes a planned download's bytes, chunk by chunk, each only after it has been verified.
   *
   * @param download the planned download
   * @param out the response body
   * @throws IOException if the client stops reading
   * @throws DownloadAbortedException if every copy of a chunk failed, or the whole-object checksum
   *     did not match; the bytes already written are correct but incomplete
   */
  public void write(Download download, OutputStream out) throws IOException {
    String objectId = download.object().objectId();
    MessageDigest wholeObject = Checksums.newSha256();
    List<PlannedChunk> chunks = download.chunks();
    for (int i = 0; i < chunks.size(); i++) {
      byte[] bytes = readVerified(objectId, chunks.get(i));
      wholeObject.update(bytes);
      if (i == chunks.size() - 1
          && !Checksums.toHex(wholeObject.digest()).equals(download.object().checksumSha256())) {
        // Every chunk matched its own checksum, so the metadata itself is inconsistent.
        log.atError()
            .addKeyValue("operation", "download")
            .addKeyValue("object_id", objectId)
            .log(
                "Whole-object checksum does not match the metadata; aborting before the last chunk");
        throw new DownloadAbortedException("Whole-object checksum mismatch for " + objectId);
      }
      out.write(bytes);
    }
    out.flush();
  }

  /** Reads one chunk from the first copy that passes the checks. */
  private byte[] readVerified(String objectId, PlannedChunk planned) {
    ChunkRow chunk = planned.chunk();
    for (ReplicaLocation copy : planned.copies()) {
      try {
        byte[] bytes =
            workerClient.getChunk(new WorkerRef(copy.workerId(), copy.url()), chunk.chunkId());
        if (bytes.length == chunk.size()
            && Checksums.sha256Hex(bytes).equals(chunk.checksumSha256())) {
          return bytes;
        }
        // The worker checked against its file name, which is not proof (decision 0005).
        mark(copy, ReplicaStatus.CORRUPT, "Copy does not match the metadata checksum");
      } catch (WorkerCallException e) {
        if (e.failure() == Failure.CORRUPT) {
          mark(copy, ReplicaStatus.CORRUPT, "Worker found its copy corrupt and quarantined it");
        } else if (e.failure() == Failure.NOT_FOUND) {
          mark(copy, ReplicaStatus.LOST, "Worker has no copy");
        } else {
          log.atDebug()
              .addKeyValue("operation", "download")
              .addKeyValue("chunk_id", chunk.chunkId())
              .addKeyValue("worker_id", copy.workerId())
              .log("Copy not readable ({}); trying the next one", e.failure());
        }
      }
    }
    log.atError()
        .addKeyValue("operation", "download")
        .addKeyValue("object_id", objectId)
        .addKeyValue("chunk_id", chunk.chunkId())
        .log("Every copy of the chunk failed; aborting the download");
    throw new DownloadAbortedException("No readable copy of chunk " + chunk.chunkId());
  }

  private void mark(ReplicaLocation copy, ReplicaStatus status, String why) {
    transactions.executeWithoutResult(tx -> {
      if (status == ReplicaStatus.CORRUPT) {
        replicas.markCorrupt(copy.chunkId(), copy.workerId());
      } else {
        replicas.markLost(copy.chunkId(), copy.workerId());
      }
    });
    log.atWarn()
        .addKeyValue("operation", "download")
        .addKeyValue("chunk_id", copy.chunkId())
        .addKeyValue("worker_id", copy.workerId())
        .log("{}; marked it {} and trying the next copy", why, status);
  }
}
