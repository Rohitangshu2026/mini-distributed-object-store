package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A chunk of a committed object and how many usable copies it has.
 *
 * @param chunkId the chunk's ID
 * @param objectId the object it belongs to
 * @param chunkIndex its position in the object
 * @param size its size in bytes
 * @param checksumSha256 its SHA-256
 * @param healthyCopies its HEALTHY copies on ALIVE workers; copies on SUSPECT workers do not count
 */
public record ChunkHealth(
    String chunkId,
    String objectId,
    int chunkIndex,
    int size,
    String checksumSha256,
    int healthyCopies) {}
