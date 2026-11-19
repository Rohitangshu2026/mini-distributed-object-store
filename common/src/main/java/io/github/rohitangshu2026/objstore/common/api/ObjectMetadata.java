package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An object's full metadata: {@code GET /api/v1/objects/{object_id}/metadata}. Serialized in
 * snake_case.
 *
 * @param objectId the object's UUID v7
 * @param name the object's name
 * @param size the object's size in bytes, or {@code null} while it is uploading
 * @param contentType the object's content type
 * @param checksumSha256 the SHA-256 of the whole object, or {@code null} while it is uploading
 * @param chunkCount how many chunks it has, or {@code null} while it is uploading
 * @param status {@code UPLOADING}, {@code COMMITTED} or {@code DELETING}
 * @param createdAt when the object was created, in RFC 3339 UTC
 * @param updatedAt when the object was last changed, in RFC 3339 UTC
 * @param customMetadata the custom metadata, a flat JSON object
 * @param chunks the chunks in order, each with the workers that hold its copies
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ObjectMetadata(
    String objectId,
    String name,
    Long size,
    String contentType,
    String checksumSha256,
    Integer chunkCount,
    String status,
    String createdAt,
    String updatedAt,
    JsonNode customMetadata,
    List<ChunkView> chunks) {}
