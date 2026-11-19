package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A row of the {@code chunks} table.
 *
 * @param chunkId the chunk's ID, {@code {object_id}-{chunk_index}}
 * @param objectId the ID of the object the chunk belongs to
 * @param chunkIndex the chunk's position in the object, from 0
 * @param size the chunk's size in bytes
 * @param checksumSha256 the chunk's SHA-256, as 64 lowercase hex characters
 */
public record ChunkRow(
    String chunkId, String objectId, int chunkIndex, int size, String checksumSha256) {}
