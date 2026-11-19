package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;

/**
 * The cluster view returned by {@code GET /internal/workers}.
 *
 * @param workers every registered worker, sorted by worker ID
 */
public record WorkerList(List<WorkerView> workers) {}
