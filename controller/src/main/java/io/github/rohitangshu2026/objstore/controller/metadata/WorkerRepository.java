package io.github.rohitangshu2026.objstore.controller.metadata;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the registered workers.
 *
 * <p>Callers own the transaction boundaries, as for {@link ObjectRepository}. Every method throws
 * {@link org.springframework.dao.DataAccessException} if the database rejects the statement.
 */
@Repository
public class WorkerRepository {

  private static final String COLUMNS = "worker_id, url, state, last_heartbeat_at, registered_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the client for the metadata database
   */
  public WorkerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a worker.
   *
   * @param worker the new worker
   */
  public void insert(WorkerRow worker) {
    jdbc.sql(
            "INSERT INTO workers ("
                + COLUMNS
                + ") VALUES (:workerId, :url, :state, :lastHeartbeatAt, :registeredAt)")
        .param("workerId", worker.workerId())
        .param("url", worker.url())
        .param("state", worker.state().name())
        .param("lastHeartbeatAt", worker.lastHeartbeatAt())
        .param("registeredAt", worker.registeredAt())
        .update();
  }

  /**
   * Finds a worker by its ID.
   *
   * @param workerId the worker's ID
   * @return the worker, or empty if it has never registered
   */
  public Optional<WorkerRow> find(String workerId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM workers WHERE worker_id = ?")
        .param(workerId)
        .query(WorkerRow.class)
        .optional();
  }

  /**
   * Returns every registered worker.
   *
   * @return the workers, sorted by worker ID
   */
  public List<WorkerRow> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM workers ORDER BY worker_id")
        .query(WorkerRow.class)
        .list();
  }
}
