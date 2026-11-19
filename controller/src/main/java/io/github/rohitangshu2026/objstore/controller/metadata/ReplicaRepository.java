package io.github.rohitangshu2026.objstore.controller.metadata;

import java.util.List;
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
