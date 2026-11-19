package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An object's system and custom metadata, as returned by an upload ({@code docs/api.md} §3).
 * Serialized in snake_case.
 *
 * @param objectId the object's UUID v7
 * @param name the object's name
 * @param size the object's size in bytes
 * @param contentType the object's content type
 * @param checksumSha256 the SHA-256 of the whole object
 * @param chunkCount how many chunks the object has
 * @param createdAt when the object was created, in RFC 3339 UTC
 * @param updatedAt when the object was last changed, in RFC 3339 UTC
 * @param customMetadata the custom metadata, a flat JSON object
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ObjectSummary(
    String objectId,
    String name,
    long size,
    String contentType,
    String checksumSha256,
    int chunkCount,
    String createdAt,
    String updatedAt,
    JsonNode customMetadata) {}
