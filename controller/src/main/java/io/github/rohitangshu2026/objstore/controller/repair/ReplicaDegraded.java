package io.github.rohitangshu2026.objstore.controller.repair;

/**
 * Published after a copy has been marked CORRUPT or LOST, so repair runs without waiting for its
 * next sweep.
 *
 * @param chunkId the chunk whose copy degraded
 * @param workerId the worker that holds the copy
 */
public record ReplicaDegraded(String chunkId, String workerId) {}
