package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What the metadata says a worker should hold: {@code GET
 * /internal/workers/{worker_id}/chunks?view=expected}. Serialized in snake_case.
 *
 * @param workerId the worker's ID
 * @param view always {@code expected}
 * @param chunks the chunk copies recorded on the worker, sorted by chunk ID
 * @param tombstones the IDs of chunks whose copy on the worker is due for deletion, sorted
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ExpectedInventory(
    String workerId, String view, List<ExpectedChunk> chunks, List<String> tombstones) {

  /**
   * One chunk copy recorded on the worker. Serialized in snake_case.
   *
   * @param chunkId the chunk's ID
   * @param size the chunk's size in bytes
   * @param checksumSha256 the chunk's SHA-256
   * @param status the copy's status: {@code PENDING}, {@code HEALTHY}, {@code LOST} or {@code
   *     CORRUPT}
   */
  @JsonNaming(SnakeCaseStrategy.class)
  public record ExpectedChunk(String chunkId, long size, String checksumSha256, String status) {}
}
