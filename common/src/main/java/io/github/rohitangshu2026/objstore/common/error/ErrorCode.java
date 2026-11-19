package io.github.rohitangshu2026.objstore.common.error;

/**
 * The stable error codes of {@code docs/api.md} §2, each paired with its HTTP status.
 *
 * <p>Clients see {@link #code()} in the {@code error} field of an {@link ErrorBody}. The codes are
 * part of the API contract, so an existing code never changes; later phases add the codes they
 * need.
 */
public enum ErrorCode {

  /** The request is malformed in a way that no more specific code describes. */
  INVALID_REQUEST(400, "invalid_request"),

  /** A chunk ID in the path is not in the chunk ID format. */
  INVALID_CHUNK_ID(400, "invalid_chunk_id"),

  /** A chunk PUT has no {@code X-Checksum-SHA256} header, or one that is not a checksum. */
  MISSING_CHECKSUM(400, "missing_checksum"),

  /** A chunk PUT body is empty or larger than {@code Chunking.CHUNK_SIZE} bytes. */
  INVALID_CHUNK_SIZE(400, "invalid_chunk_size"),

  /** A chunk PUT body does not match its checksum header; nothing was stored. */
  BODY_CHECKSUM_MISMATCH(400, "checksum_mismatch"),

  /** A stored copy no longer matches its recorded checksum and has been quarantined. */
  STORED_CHECKSUM_MISMATCH(409, "checksum_mismatch"),

  /** An object ID in the path is not a lowercase canonical UUID. */
  INVALID_OBJECT_ID(400, "invalid_object_id"),

  /** An upload has no {@code file} part. */
  MISSING_FILE(400, "missing_file"),

  /** Custom metadata breaks a rule of {@code docs/api.md} §6. */
  INVALID_METADATA(400, "invalid_metadata"),

  /** No object has the requested ID. */
  OBJECT_NOT_FOUND(404, "object_not_found"),

  /** The object is still uploading or is being deleted. */
  OBJECT_NOT_COMMITTED(409, "object_not_committed"),

  /** No endpoint exists at the requested path. */
  NOT_FOUND(404, "not_found"),

  /** The worker holds no servable copy of the requested chunk. */
  CHUNK_NOT_FOUND(404, "chunk_not_found"),

  /** The worker is not registered with the controller, or the path names a different worker. */
  WORKER_NOT_FOUND(404, "worker_not_found"),

  /** The endpoint exists but does not support the request's HTTP method. */
  METHOD_NOT_ALLOWED(405, "method_not_allowed"),

  /** An unexpected failure; the details are only in the server's logs. */
  INTERNAL_ERROR(500, "internal_error"),

  /** A disk operation on the worker failed; nothing was changed or confirmed. */
  STORAGE_UNAVAILABLE(503, "storage_unavailable"),

  /** The controller could not reach a worker, or could not read its inventory completely. */
  WORKER_UNAVAILABLE(503, "worker_unavailable"),

  /** Fewer than 2 workers are ALIVE, or no eligible worker is left for a copy; nothing stored. */
  INSUFFICIENT_WORKERS(503, "insufficient_workers"),

  /** Before a download started, some chunk had no HEALTHY copy on an ALIVE or SUSPECT worker. */
  CHUNK_UNAVAILABLE(503, "chunk_unavailable");

  private final int status;
  private final String code;

  ErrorCode(int status, String code) {
    this.status = status;
    this.code = code;
  }

  /** {@return the HTTP status sent with this code, for example 404} */
  public int status() {
    return status;
  }

  /** {@return the code clients see in the {@code error} field, for example {@code not_found}} */
  public String code() {
    return code;
  }
}
