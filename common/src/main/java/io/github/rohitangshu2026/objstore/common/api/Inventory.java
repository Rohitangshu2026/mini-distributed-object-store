package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What a worker actually holds on disk, as returned by {@code GET
 * /internal/workers/{worker_id}/chunks}. Serialized in snake_case.
 *
 * @param workerId the reporting worker's ID
 * @param chunks the worker's servable chunks, sorted by chunk ID
 * @param quarantined the IDs of chunks that have a quarantined copy, sorted
 */
@JsonNaming(SnakeCaseStrategy.class)
public record Inventory(String workerId, List<ChunkInfo> chunks, List<String> quarantined) {}
