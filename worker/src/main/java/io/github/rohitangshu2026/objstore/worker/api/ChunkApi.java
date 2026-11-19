package io.github.rohitangshu2026.objstore.worker.api;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.common.api.ChunkInfo;
import io.github.rohitangshu2026.objstore.common.api.Inventory;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore.VerifiedChunk;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The worker's internal chunk API ({@code docs/api.md} §5): stores, returns and deletes chunks, and
 * reports what the worker holds. Only the controller calls it, because workers are reachable only
 * on the Compose network.
 */
@RestController
class ChunkApi {

  /** The request and response header that carries a chunk's SHA-256. */
  private static final String CHECKSUM_HEADER = "X-Checksum-SHA256";

  private final ChunkStore store;
  private final WorkerProperties properties;

  ChunkApi(ChunkStore store, WorkerProperties properties) {
    this.store = store;
    this.properties = properties;
  }

  /**
   * Stores a chunk: {@code PUT /internal/chunks/{chunk_id}}.
   *
   * <p>Reads the raw request stream rather than a converted body, so the bytes are hashed as they
   * arrive and never held in memory whole.
   *
   * @param chunkId the chunk ID from the path
   * @param checksum the {@code X-Checksum-SHA256} header: the SHA-256 the body must match
   * @param body the request body
   * @return the stored chunk's ID, size and checksum, sent with status 200
   * @throws ApiException {@link ErrorCode#INVALID_CHUNK_ID} or {@link ErrorCode#MISSING_CHECKSUM}
   *     before anything is written, or any failure of {@link ChunkStore#put}
   */
  @PutMapping("/internal/chunks/{chunkId}")
  ChunkInfo put(
      @PathVariable String chunkId,
      @RequestHeader(name = CHECKSUM_HEADER, required = false) String checksum,
      InputStream body) {
    ChunkId id = parse(chunkId);
    if (!Checksums.isSha256Hex(checksum)) {
      throw new ApiException(
          ErrorCode.MISSING_CHECKSUM, CHECKSUM_HEADER + " must be 64 lowercase hex characters");
    }
    return store.put(id, checksum, body);
  }

  /**
   * Returns a chunk's bytes: {@code GET /internal/chunks/{chunk_id}}. The bytes were re-hashed and
   * matched their recorded checksum before the response starts.
   *
   * @param chunkId the chunk ID from the path
   * @return the bytes as {@code application/octet-stream}, with {@code Content-Length} and the
   *     {@code X-Checksum-SHA256} they matched
   * @throws ApiException {@link ErrorCode#INVALID_CHUNK_ID}, or any failure of {@link
   *     ChunkStore#get}
   */
  @GetMapping("/internal/chunks/{chunkId}")
  ResponseEntity<byte[]> get(@PathVariable String chunkId) {
    VerifiedChunk chunk = store.get(parse(chunkId));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(chunk.bytes().length)
        .header(CHECKSUM_HEADER, chunk.checksum())
        .body(chunk.bytes());
  }

  /**
   * Deletes a chunk: {@code DELETE /internal/chunks/{chunk_id}}. Answers 204 whether or not the
   * chunk existed.
   *
   * @param chunkId the chunk ID from the path
   * @throws ApiException {@link ErrorCode#INVALID_CHUNK_ID}, or {@link
   *     ErrorCode#STORAGE_UNAVAILABLE} if the delete could not be confirmed
   */
  @DeleteMapping("/internal/chunks/{chunkId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String chunkId) {
    store.delete(parse(chunkId));
  }

  /**
   * Reports what this worker actually holds: {@code GET /internal/workers/{worker_id}/chunks}.
   *
   * @param workerId the worker ID from the path, which must be this worker's
   * @return the servable chunks from the index, and the quarantined chunk IDs from a disk scan
   * @throws ApiException {@link ErrorCode#WORKER_NOT_FOUND} if the path names another worker
   */
  @GetMapping("/internal/workers/{workerId}/chunks")
  Inventory inventory(@PathVariable String workerId) {
    if (!workerId.equals(properties.workerId())) {
      throw new ApiException(ErrorCode.WORKER_NOT_FOUND, "This is worker " + properties.workerId());
    }
    return new Inventory(properties.workerId(), store.chunks(), store.quarantined());
  }

  /** Checks the ID before anything else uses it, so no file path is built from an unchecked ID. */
  private static ChunkId parse(String chunkId) {
    if (!ChunkId.isValid(chunkId)) {
      throw new ApiException(
          ErrorCode.INVALID_CHUNK_ID,
          "A chunk ID is an object ID, a hyphen, and the chunk index padded to 6 digits");
    }
    return new ChunkId(chunkId);
  }
}
