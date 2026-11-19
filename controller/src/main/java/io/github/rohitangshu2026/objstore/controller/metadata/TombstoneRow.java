package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A row of the {@code tombstones} table: a durable intent to delete one copy of a chunk.
 *
 * @param chunkId the chunk's ID; the chunk's own row may already be gone
 * @param workerId the ID of the worker that holds the copy
 * @param reason why the copy is to be deleted
 * @param createdAt when the tombstone was recorded, in epoch milliseconds
 */
public record TombstoneRow(
    String chunkId, String workerId, TombstoneReason reason, long createdAt) {}
