package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A worker announcing itself to the controller: {@code POST /internal/workers/register}. Serialized
 * in snake_case.
 *
 * @param workerId the worker's ID, matching {@code ^[a-z0-9-]{1,32}$}
 * @param url the absolute {@code http} or {@code https} URL the controller should call it on
 * @param bootCount how many times the worker has started, including this time
 */
@JsonNaming(SnakeCaseStrategy.class)
public record RegisterRequest(String workerId, String url, long bootCount) {}
