package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A row of the {@code chunk_replicas} table: one copy of a chunk on one worker.
 *
 * @param chunkId the chunk's ID
 * @param workerId the ID of the worker that holds the copy
 * @param status the copy's state
 * @param lastVerifiedAt when the copy last passed verification, in epoch milliseconds, or {@code
 *     null} if it never has
 */
public record ReplicaRow(
    String chunkId, String workerId, ReplicaStatus status, Long lastVerifiedAt) {}
