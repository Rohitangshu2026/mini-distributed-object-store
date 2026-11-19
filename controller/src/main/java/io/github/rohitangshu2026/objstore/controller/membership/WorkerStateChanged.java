package io.github.rohitangshu2026.objstore.controller.membership;

import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;

/**
 * Published after the membership check has committed a worker's new state, so repair and
 * reconciliation can react without depending on {@link Membership}.
 *
 * @param workerId the worker's ID
 * @param from its previous state
 * @param to its new state
 */
public record WorkerStateChanged(String workerId, WorkerState from, WorkerState to) {}
