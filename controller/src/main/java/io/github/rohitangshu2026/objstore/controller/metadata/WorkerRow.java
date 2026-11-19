package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * A row of the {@code workers} table.
 *
 * @param workerId the worker's ID, for example {@code worker2}
 * @param url the base URL the controller uses to reach the worker
 * @param state what the controller believes about the worker's liveness
 * @param lastHeartbeatAt when the worker last registered or changed state, in epoch milliseconds
 * @param registeredAt when the worker first registered, in epoch milliseconds
 */
public record WorkerRow(
    String workerId, String url, WorkerState state, long lastHeartbeatAt, long registeredAt) {}
