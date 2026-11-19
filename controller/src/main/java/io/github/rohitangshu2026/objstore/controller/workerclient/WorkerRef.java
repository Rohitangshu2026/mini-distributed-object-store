package io.github.rohitangshu2026.objstore.controller.workerclient;

/**
 * The address of a worker to call.
 *
 * @param workerId the worker's ID
 * @param url the worker's base URL, for example {@code http://worker2:9000}
 */
public record WorkerRef(String workerId, String url) {}
