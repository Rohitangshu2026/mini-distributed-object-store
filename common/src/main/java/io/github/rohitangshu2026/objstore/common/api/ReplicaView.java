package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One copy of a chunk in an object's metadata. Serialized in snake_case.
 *
 * @param workerId the worker that holds the copy
 * @param status {@code PENDING}, {@code HEALTHY}, {@code LOST} or {@code CORRUPT}
 * @param lastVerifiedAt when the copy was last verified, in RFC 3339 UTC, or {@code null} if never
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ReplicaView(String workerId, String status, String lastVerifiedAt) {}
