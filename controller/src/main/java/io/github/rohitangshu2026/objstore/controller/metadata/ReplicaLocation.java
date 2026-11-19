package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A chunk copy together with the worker that holds it, as downloads and the metadata view need.
 *
 * @param chunkId the chunk's ID
 * @param workerId the worker that holds the copy
 * @param status the copy's status
 * @param lastVerifiedAt when the copy was last verified, in epoch milliseconds, or {@code null}
 * @param url the worker's URL
 * @param workerState the worker's state
 */
public record ReplicaLocation(
    String chunkId,
    String workerId,
    ReplicaStatus status,
    Long lastVerifiedAt,
    String url,
    WorkerState workerState) {}
