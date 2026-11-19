package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One object in a listing. Serialized in snake_case.
 *
 * @param objectId the object's UUID v7
 * @param name the object's name
 * @param size the object's size in bytes
 * @param contentType the object's content type
 * @param createdAt when the object was created, in RFC 3339 UTC
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ObjectListEntry(
    String objectId, String name, long size, String contentType, String createdAt) {}
