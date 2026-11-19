package io.github.rohitangshu2026.objstore.controller.workerclient;

import io.github.rohitangshu2026.objstore.common.api.Inventory;

/**
 * The controller's only way to call a worker (rule A7). Tests replace it with a fake that injects
 * failures; later phases add the chunk operations as they need them.
 */
public interface WorkerClient {

  /**
   * Fetches what a worker actually holds on disk.
   *
   * @param worker the worker to ask
   * @return its inventory
   * @throws WorkerCallException if the worker could not be asked or did not answer completely
   */
  Inventory inventory(WorkerRef worker);
}
