package io.github.rohitangshu2026.objstore.controller.workerclient;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.api.ChunkInfo;
import io.github.rohitangshu2026.objstore.common.api.Inventory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory workers for tests, with switches that inject the failures of {@code
 * docs/architecture.md} §10.1: a worker that is down, a write that is applied but whose answer is
 * lost, a read that fails in a chosen way, and a copy corrupted behind the worker's back.
 */
public final class FakeWorkerClient implements WorkerClient {

  private final Map<String, Map<String, byte[]>> stored = new ConcurrentHashMap<>();
  private final Set<String> down = ConcurrentHashMap.newKeySet();
  private final Set<String> losesWriteAnswers = ConcurrentHashMap.newKeySet();
  private final Map<String, Failure> failingReads = new ConcurrentHashMap<>();

  /** Makes every call to a worker fail as unreachable. */
  public void stop(String workerId) {
    down.add(workerId);
  }

  /** Makes a stopped worker answer again. */
  public void start(String workerId) {
    down.remove(workerId);
  }

  /** Makes a worker store each write but lose its answer, an unknown outcome. */
  public void loseWriteAnswers(String workerId) {
    losesWriteAnswers.add(workerId);
  }

  /** Makes reads of one chunk from one worker fail with {@code failure}. */
  public void failReads(String workerId, String chunkId, Failure failure) {
    failingReads.put(workerId + "/" + chunkId, failure);
  }

  /** Flips a bit in the copy a worker holds, which only the controller's own check can catch. */
  public void corruptSilently(String workerId, String chunkId) {
    chunksOn(workerId).get(chunkId)[0] ^= 1;
  }

  /** {@return the chunks a worker holds, by chunk ID} */
  public Map<String, byte[]> chunksOn(String workerId) {
    return stored.getOrDefault(workerId, Map.of());
  }

  @Override
  public void putChunk(
      WorkerRef worker, String chunkId, byte[] bytes, int length, String checksum) {
    failIfDown(worker, "put_chunk");
    stored
        .computeIfAbsent(worker.workerId(), id -> new ConcurrentHashMap<>())
        .put(chunkId, Arrays.copyOf(bytes, length));
    if (losesWriteAnswers.contains(worker.workerId())) {
      throw new WorkerCallException(Failure.OUTCOME_UNKNOWN, worker.workerId(), "put_chunk", null);
    }
  }

  @Override
  public byte[] getChunk(WorkerRef worker, String chunkId) {
    failIfDown(worker, "get_chunk");
    Failure failure = failingReads.get(worker.workerId() + "/" + chunkId);
    if (failure != null) {
      throw new WorkerCallException(failure, worker.workerId(), "get_chunk", null);
    }
    byte[] bytes = chunksOn(worker.workerId()).get(chunkId);
    if (bytes == null) {
      throw new WorkerCallException(Failure.NOT_FOUND, worker.workerId(), "get_chunk", null);
    }
    return bytes.clone();
  }

  @Override
  public void deleteChunk(WorkerRef worker, String chunkId) {
    failIfDown(worker, "delete_chunk");
    stored.getOrDefault(worker.workerId(), new ConcurrentHashMap<>()).remove(chunkId);
  }

  @Override
  public Inventory inventory(WorkerRef worker) {
    failIfDown(worker, "inventory");
    List<ChunkInfo> chunks =
        chunksOn(worker.workerId()).entrySet().stream()
            .map(e ->
                new ChunkInfo(e.getKey(), e.getValue().length, Checksums.sha256Hex(e.getValue())))
            .sorted((a, b) -> a.chunkId().compareTo(b.chunkId()))
            .toList();
    return new Inventory(worker.workerId(), chunks, List.of());
  }

  private void failIfDown(WorkerRef worker, String operation) {
    if (down.contains(worker.workerId())) {
      throw new WorkerCallException(Failure.UNREACHABLE, worker.workerId(), operation, null);
    }
  }
}
