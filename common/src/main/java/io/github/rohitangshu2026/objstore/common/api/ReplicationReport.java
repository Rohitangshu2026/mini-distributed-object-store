package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * How well the chunks of committed objects are replicated: {@code GET /internal/replication}.
 * Serialized in snake_case. Only HEALTHY copies on ALIVE workers count.
 *
 * @param replicationFactor the number of copies every chunk should have, 2
 * @param totalChunks every chunk of a committed object
 * @param healthy chunks with 2 or more counted copies
 * @param underReplicated chunks with exactly 1
 * @param unavailable chunks with none
 * @param pendingTombstones copies waiting to be deleted
 * @param chunks the chunks that are not healthy, at most 1000, sorted by chunk ID
 * @param truncated whether more chunks are not healthy than are listed
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ReplicationReport(
    int replicationFactor,
    long totalChunks,
    long healthy,
    long underReplicated,
    long unavailable,
    long pendingTombstones,
    List<Chunk> chunks,
    boolean truncated) {

  /**
   * A chunk that is not healthy. Serialized in snake_case.
   *
   * @param chunkId the chunk's ID
   * @param objectId the object it belongs to
   * @param healthyCopies its HEALTHY copies on ALIVE workers
   * @param replicas every copy recorded for it
   */
  @JsonNaming(SnakeCaseStrategy.class)
  public record Chunk(String chunkId, String objectId, int healthyCopies, List<Copy> replicas) {}

  /**
   * One recorded copy of a chunk. Serialized in snake_case.
   *
   * @param workerId the worker that holds it
   * @param status the copy's status
   * @param workerState the worker's state
   */
  @JsonNaming(SnakeCaseStrategy.class)
  public record Copy(String workerId, String status, String workerState) {}
}
