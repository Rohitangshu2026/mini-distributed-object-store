package io.github.rohitangshu2026.objstore.controller.metadata;

import io.github.rohitangshu2026.objstore.common.api.ExpectedInventory.ExpectedChunk;
import java.util.Collection;
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

  /** Counts a chunk's HEALTHY copies on ALIVE workers; {@code c} is the chunk. */
  private static final String HEALTHY_ON_ALIVE =
      "(SELECT count(*) FROM chunk_replicas hr JOIN workers hw ON hw.worker_id = hr.worker_id"
          + " WHERE hr.chunk_id = c.chunk_id AND hr.status = 'HEALTHY' AND hw.state = 'ALIVE')";

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
   * Marks one copy LOST if it is HEALTHY, because its worker no longer has it.
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker that should hold the copy
   * @return {@code true} if the copy was HEALTHY and is now LOST
   */
  public boolean markLost(String chunkId, String workerId) {
    return jdbc.sql(
                "UPDATE chunk_replicas SET status = 'LOST'"
                    + " WHERE chunk_id = ? AND worker_id = ? AND status = 'HEALTHY'")
            .param(chunkId)
            .param(workerId)
            .update()
        == 1;
  }

  /**
   * Returns every copy of an object's chunks with its worker's URL and state, in one query.
   *
   * @param objectId the object's ID
   * @return the copies, sorted by chunk ID and worker ID
   */
  public List<ReplicaLocation> locationsOf(String objectId) {
    return jdbc.sql(
            "SELECT r.chunk_id, r.worker_id, r.status, r.last_verified_at, w.url,"
                + " w.state AS worker_state FROM chunk_replicas r"
                + " JOIN chunks c ON c.chunk_id = r.chunk_id"
                + " JOIN workers w ON w.worker_id = r.worker_id"
                + " WHERE c.object_id = ? ORDER BY r.chunk_id, r.worker_id")
        .param(objectId)
        .query(ReplicaLocation.class)
        .list();
  }

  /**
   * Returns the chunks of committed objects that have fewer than 2 HEALTHY copies on ALIVE workers.
   *
   * @param limit the most chunks to return
   * @return the chunks with their counts, sorted by chunk ID
   */
  public List<ChunkHealth> underReplicated(int limit) {
    return jdbc.sql(
            "SELECT * FROM (SELECT c.chunk_id, c.object_id, c.chunk_index, c.size,"
                + " c.checksum_sha256, "
                + HEALTHY_ON_ALIVE
                + " AS healthy_copies FROM chunks c JOIN objects o ON o.object_id = c.object_id"
                + " WHERE o.status = 'COMMITTED') WHERE healthy_copies < 2"
                + " ORDER BY chunk_id LIMIT ?")
        .param(limit)
        .query(ChunkHealth.class)
        .list();
  }

  /** {@return how well the chunks of committed objects are replicated, in one query} */
  public ReplicationCounts replicationCounts() {
    return jdbc.sql(
            "SELECT count(*) AS total,"
                + " coalesce(sum(h >= 2), 0) AS healthy,"
                + " coalesce(sum(h = 1), 0) AS under_replicated,"
                + " coalesce(sum(h = 0), 0) AS unavailable"
                + " FROM (SELECT "
                + HEALTHY_ON_ALIVE
                + " AS h FROM chunks c JOIN objects o ON o.object_id = c.object_id"
                + " WHERE o.status = 'COMMITTED')")
        .query(ReplicationCounts.class)
        .single();
  }

  /**
   * Returns every copy of some chunks with its worker's URL and state, in one query.
   *
   * @param chunkIds the chunks' IDs
   * @return the copies, sorted by chunk ID and worker ID
   */
  public List<ReplicaLocation> locationsOfChunks(Collection<String> chunkIds) {
    if (chunkIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT r.chunk_id, r.worker_id, r.status, r.last_verified_at, w.url,"
                + " w.state AS worker_state FROM chunk_replicas r"
                + " JOIN workers w ON w.worker_id = r.worker_id"
                + " WHERE r.chunk_id IN (:chunkIds) ORDER BY r.chunk_id, r.worker_id")
        .param("chunkIds", chunkIds)
        .query(ReplicaLocation.class)
        .list();
  }

  /**
   * Turns every PENDING copy of a committed object into a tombstone. Run at startup: a PENDING row
   * of a committed object belongs to a repair, and no repair survives a restart.
   *
   * @param now the time in epoch milliseconds
   * @return how many copies were turned into tombstones
   */
  public int replaceInterruptedRepairsWithTombstones(long now) {
    String pending =
        " FROM chunk_replicas r JOIN chunks c ON c.chunk_id = r.chunk_id"
            + " JOIN objects o ON o.object_id = c.object_id"
            + " WHERE r.status = 'PENDING' AND o.status = 'COMMITTED'";
    jdbc.sql(
            "INSERT INTO tombstones (chunk_id, worker_id, reason, created_at)"
                + " SELECT r.chunk_id, r.worker_id, 'REPAIR_INTERRUPTED', ?"
                + pending
                + " ON CONFLICT DO NOTHING")
        .param(now)
        .update();
    return jdbc.sql(
            "DELETE FROM chunk_replicas WHERE (chunk_id, worker_id) IN"
                + " (SELECT r.chunk_id, r.worker_id"
                + pending
                + ")")
        .update();
  }

  /**
   * Counts a chunk's HEALTHY copies on ALIVE workers other than one: the N5 check before a copy on
   * that worker may be deleted.
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker whose copy would be deleted
   * @return how many HEALTHY copies would remain on ALIVE workers
   */
  public int healthyOnAliveExcept(String chunkId, String workerId) {
    return jdbc.sql(
            "SELECT count(*) FROM chunk_replicas r JOIN workers w ON w.worker_id = r.worker_id"
                + " WHERE r.chunk_id = ? AND r.worker_id <> ?"
                + " AND r.status = 'HEALTHY' AND w.state = 'ALIVE'")
        .param(chunkId)
        .param(workerId)
        .query(Integer.class)
        .single();
  }

  /**
   * Deletes one copy's row, for a LOST copy whose file is gone and that is not needed.
   *
   * @param chunkId the chunk's ID
   * @param workerId the worker that held the copy
   */
  public void delete(String chunkId, String workerId) {
    jdbc.sql("DELETE FROM chunk_replicas WHERE chunk_id = ? AND worker_id = ?")
        .param(chunkId)
        .param(workerId)
        .update();
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
