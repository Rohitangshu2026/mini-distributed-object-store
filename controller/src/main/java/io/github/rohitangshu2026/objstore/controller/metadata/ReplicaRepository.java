package io.github.rohitangshu2026.objstore.controller.metadata;

import io.github.rohitangshu2026.objstore.common.api.ExpectedInventory.ExpectedChunk;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the copies of chunks held by workers.
 *
 * <p>Callers own the transaction boundaries, as for {@link ObjectRepository}. Every method throws
 * {@link org.springframework.dao.DataAccessException} if the database rejects the statement; a
 * second copy of a chunk on the same worker is rejected by the primary key (invariant I4).
 */
@Repository
public class ReplicaRepository {

  private static final String COLUMNS = "chunk_id, worker_id, status, last_verified_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the client for the metadata database
   */
  public ReplicaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a copy of a chunk.
   *
   * @param replica the new copy, whose chunk and worker must exist
   */
  public void insert(ReplicaRow replica) {
    jdbc.sql(
            "INSERT INTO chunk_replicas ("
                + COLUMNS
                + ") VALUES (:chunkId, :workerId, :status, :lastVerifiedAt)")
        .param("chunkId", replica.chunkId())
        .param("workerId", replica.workerId())
        .param("status", replica.status().name())
        .param("lastVerifiedAt", replica.lastVerifiedAt())
        .update();
  }

  /**
   * Marks a copy HEALTHY once its worker has confirmed a verified write (rule N2).
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker that holds the copy
   * @param verifiedAt when the write was confirmed, in epoch milliseconds
   */
  public void markHealthy(String chunkId, String workerId, long verifiedAt) {
    jdbc.sql(
            "UPDATE chunk_replicas SET status = 'HEALTHY', last_verified_at = :verifiedAt"
                + " WHERE chunk_id = :chunkId AND worker_id = :workerId")
        .param("verifiedAt", verifiedAt)
        .param("chunkId", chunkId)
        .param("workerId", workerId)
        .update();
  }

  /**
   * Replaces one copy's row with a tombstone, the durable intent to delete that copy.
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker that holds the copy
   * @param reason why the copy must go
   * @param now the time in epoch milliseconds
   */
  public void replaceWithTombstone(
      String chunkId, String workerId, TombstoneReason reason, long now) {
    jdbc.sql("DELETE FROM chunk_replicas WHERE chunk_id = ? AND worker_id = ?")
        .param(chunkId)
        .param(workerId)
        .update();
    jdbc.sql(
            "INSERT INTO tombstones (chunk_id, worker_id, reason, created_at)"
                + " VALUES (:chunkId, :workerId, :reason, :now) ON CONFLICT DO NOTHING")
        .param("chunkId", chunkId)
        .param("workerId", workerId)
        .param("reason", reason.name())
        .param("now", now)
        .update();
  }

  /**
   * Replaces the row of every copy of an object's chunks with a tombstone.
   *
   * @param objectId the object's ID
   * @param reason why the copies must go
   * @param now the time in epoch milliseconds
   * @return how many copies were turned into tombstones
   */
  public int replaceAllWithTombstones(String objectId, TombstoneReason reason, long now) {
    jdbc.sql(
            "INSERT INTO tombstones (chunk_id, worker_id, reason, created_at)"
                + " SELECT r.chunk_id, r.worker_id, :reason, :now FROM chunk_replicas r"
                + " JOIN chunks c ON c.chunk_id = r.chunk_id WHERE c.object_id = :objectId"
                + " ON CONFLICT DO NOTHING")
        .param("reason", reason.name())
        .param("now", now)
        .param("objectId", objectId)
        .update();
    return jdbc.sql(
            "DELETE FROM chunk_replicas"
                + " WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE object_id = ?)")
        .param(objectId)
        .update();
  }

  /**
   * Counts the copy rows of any status on each worker, the load that placement balances.
   *
   * @return the count for each worker that has at least one row
   */
  public Map<String, Long> rowCountByWorker() {
    return jdbc
        .sql("SELECT worker_id, count(*) AS n FROM chunk_replicas GROUP BY worker_id")
        .query((rs, rowNum) -> Map.entry(rs.getString("worker_id"), rs.getLong("n")))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Returns the copies the metadata records on one worker, with their chunks' sizes and checksums.
   *
   * @param workerId the worker's ID
   * @return the copies, sorted by chunk ID
   */
  public List<ExpectedChunk> expectedOn(String workerId) {
    return jdbc.sql(
            "SELECT r.chunk_id, c.size, c.checksum_sha256, r.status FROM chunk_replicas r"
                + " JOIN chunks c ON c.chunk_id = r.chunk_id WHERE r.worker_id = ?"
                + " ORDER BY r.chunk_id")
        .param(workerId)
        .query(ExpectedChunk.class)
        .list();
  }

  /**
   * Marks every HEALTHY copy on a worker LOST, because the worker has been declared DEAD.
   *
   * @param workerId the dead worker's ID
   * @return how many copies were marked
   */
  public int markLostOn(String workerId) {
    return jdbc.sql(
            "UPDATE chunk_replicas SET status = 'LOST' WHERE worker_id = ? AND status = 'HEALTHY'")
        .param(workerId)
        .update();
  }

  /**
   * Marks one copy CORRUPT if it is HEALTHY. Copies in any other state keep it, so a repeated
   * report changes nothing.
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker that holds the copy
   * @return {@code true} if the copy was HEALTHY and is now CORRUPT
   */
  public boolean markCorrupt(String chunkId, String workerId) {
    return jdbc.sql(
                "UPDATE chunk_replicas SET status = 'CORRUPT'"
                    + " WHERE chunk_id = ? AND worker_id = ? AND status = 'HEALTHY'")
            .param(chunkId)
            .param(workerId)
            .update()
        == 1;
  }

  /**
   * Counts the HEALTHY copies on each worker.
   *
   * @return the count for each worker that holds at least one HEALTHY copy
   */
  public Map<String, Long> healthyCountByWorker() {
    return jdbc
        .sql(
            "SELECT worker_id, count(*) AS healthy FROM chunk_replicas"
                + " WHERE status = 'HEALTHY' GROUP BY worker_id")
        .query((rs, rowNum) -> Map.entry(rs.getString("worker_id"), rs.getLong("healthy")))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Returns every copy of a chunk.
   *
   * @param chunkId the chunk's ID
   * @return the copies, sorted by worker ID
   */
  public List<ReplicaRow> replicasOf(String chunkId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM chunk_replicas WHERE chunk_id = ? ORDER BY worker_id")
        .param(chunkId)
        .query(ReplicaRow.class)
        .list();
  }
}
