package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One worker as the controller sees it, in {@code GET /internal/workers}. Serialized in snake_case.
 *
 * @param workerId the worker's ID
 * @param url the URL the controller calls it on
 * @param state {@code ALIVE}, {@code SUSPECT} or {@code DEAD}
 * @param lastHeartbeatAt when the controller last heard from it, in RFC 3339 UTC
 * @param lastHeartbeatAgeMs how long ago that was, by the controller's clock
 * @param reportedChunkCount the chunk count from its last heartbeat, or {@code null} before one
 * @param usedBytes the bytes used from its last heartbeat, or {@code null} before one
 * @param healthyReplicas how many HEALTHY copies the metadata places on it
 */
@JsonNaming(SnakeCaseStrategy.class)
public record WorkerView(
    String workerId,
    String url,
    String state,
    String lastHeartbeatAt,
    long lastHeartbeatAgeMs,
    Long reportedChunkCount,
    Long usedBytes,
    long healthyReplicas) {}
