package io.github.rohitangshu2026.objstore.controller.workerclient;

import io.github.rohitangshu2026.objstore.common.api.Inventory;

/**
 * The controller's only way to call a worker (rule A7). Tests replace it with a fake that injects
 * failures; the read operation arrives with downloads.
 */
public interface WorkerClient {

  /**
   * Stores one chunk on a worker, which verifies the bytes against the checksum before it confirms.
   *
   * @param worker the worker to write to
   * @param chunkId the chunk's ID
   * @param bytes the buffer that holds the chunk
   * @param length how many bytes of {@code bytes}, from the start, are the chunk
   * @param checksum the chunk's SHA-256, as 64 lowercase hex characters
   * @throws WorkerCallException if the worker did not confirm a verified write
   */
  void putChunk(WorkerRef worker, String chunkId, byte[] bytes, int length, String checksum);

  /**
   * Deletes a chunk's copies from a worker. A chunk the worker does not hold counts as deleted.
   *
   * @param worker the worker to delete from
   * @param chunkId the chunk's ID
   * @throws WorkerCallException if the worker did not confirm the delete
   */
  void deleteChunk(WorkerRef worker, String chunkId);

  /**
   * Fetches what a worker actually holds on disk.
   *
   * @param worker the worker to ask
   * @return its inventory
   * @throws WorkerCallException if the worker could not be asked or did not answer completely
   */
  Inventory inventory(WorkerRef worker);
}
