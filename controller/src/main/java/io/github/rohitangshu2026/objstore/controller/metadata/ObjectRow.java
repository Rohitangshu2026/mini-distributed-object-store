package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A row of the {@code objects} table.
 *
 * @param objectId the object's UUID v7, in lowercase canonical form
 * @param name the object's name
 * @param size the object's size in bytes, or {@code null} until it is COMMITTED
 * @param contentType the object's content type
 * @param checksumSha256 the SHA-256 of the whole object, or {@code null} until it is COMMITTED
 * @param chunkCount the number of chunks, or {@code null} until it is COMMITTED
 * @param status where the object is in its lifecycle
 * @param createdAt when the object was created, in epoch milliseconds
 * @param updatedAt when the object last changed, in epoch milliseconds
 * @param customMetadata the user's metadata, as a JSON object
 */
public record ObjectRow(
    String objectId,
    String name,
    Long size,
    String contentType,
    String checksumSha256,
    Integer chunkCount,
    ObjectStatus status,
    long createdAt,
    long updatedAt,
    String customMetadata) {}
