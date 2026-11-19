package io.github.rohitangshu2026.objstore.controller.membership;

/**
 * Published after every registration: a worker that registers again may have restarted, and its
 * disk may have changed even if it was never declared DEAD, so it is reconciled.
 *
 * @param workerId the worker's ID
 */
public record WorkerRegistered(String workerId) {}
