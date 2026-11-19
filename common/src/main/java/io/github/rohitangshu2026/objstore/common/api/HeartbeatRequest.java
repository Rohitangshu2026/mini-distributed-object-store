package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A worker's periodic sign of life: {@code POST /internal/workers/heartbeat}. Serialized in
 * snake_case.
 *
 * @param workerId the worker's ID
 * @param chunkCount how many servable chunks the worker holds
 * @param usedBytes the total size of those chunks
 * @param corruptChunks the chunks the worker quarantined that no earlier heartbeat reported
 *     successfully; {@code null} is read as none
 */
@JsonNaming(SnakeCaseStrategy.class)
public record HeartbeatRequest(
    String workerId, long chunkCount, long usedBytes, List<String> corruptChunks) {}
