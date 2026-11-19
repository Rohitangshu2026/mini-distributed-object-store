package io.github.rohitangshu2026.objstore.controller.metadata;

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
