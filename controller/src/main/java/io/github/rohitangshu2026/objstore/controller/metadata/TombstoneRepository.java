package io.github.rohitangshu2026.objstore.controller.metadata;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes tombstones: durable intents to delete one copy of a chunk.
 *
 * <p>Callers own the transaction boundaries, as for {@link ObjectRepository}. Every method throws
 * {@link org.springframework.dao.DataAccessException} if the database rejects the statement.
 */
@Repository
public class TombstoneRepository {

  private static final String COLUMNS = "chunk_id, worker_id, reason, created_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the client for the metadata database
   */
  public TombstoneRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a tombstone. Its chunk need not have a row, because a tombstone outlives its chunk.
   *
   * @param tombstone the new tombstone, whose worker must exist
   */
  public void insert(TombstoneRow tombstone) {
    jdbc.sql(
            "INSERT INTO tombstones ("
                + COLUMNS
                + ") VALUES (:chunkId, :workerId, :reason, :createdAt)")
        .param("chunkId", tombstone.chunkId())
        .param("workerId", tombstone.workerId())
        .param("reason", tombstone.reason().name())
        .param("createdAt", tombstone.createdAt())
        .update();
  }

  /**
   * Returns the tombstones for copies held by one worker.
   *
   * @param workerId the worker's ID
   * @return the tombstones, sorted by chunk ID
   */
  public List<TombstoneRow> forWorker(String workerId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM tombstones WHERE worker_id = ? ORDER BY chunk_id")
        .param(workerId)
        .query(TombstoneRow.class)
        .list();
  }
}
