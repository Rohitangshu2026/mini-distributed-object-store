package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The controller's answer to a registration. Serialized in snake_case.
 *
 * @param workerId the registered worker's ID
 * @param registeredAt when the worker first registered, in RFC 3339 UTC
 */
@JsonNaming(SnakeCaseStrategy.class)
public record RegisterResponse(String workerId, String registeredAt) {}
