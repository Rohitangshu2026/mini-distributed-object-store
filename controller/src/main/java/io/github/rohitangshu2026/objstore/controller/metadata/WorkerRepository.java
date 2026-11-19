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
   * Registers a worker, or updates its URL if it is already registered. A new worker starts as
   * SUSPECT; an existing one keeps its state, which only the membership check changes.
   *
   * @param workerId the worker's ID
   * @param url the URL to call it on
   * @param now the controller's current time in epoch milliseconds
   * @return the worker as stored
   */
  public WorkerRow register(String workerId, String url, long now) {
    jdbc.sql(
            "INSERT INTO workers ("
                + COLUMNS
                + ") VALUES (:workerId, :url, 'SUSPECT', :now, :now)"
                + " ON CONFLICT (worker_id) DO UPDATE SET url = excluded.url")
        .param("workerId", workerId)
        .param("url", url)
        .param("now", now)
        .update();
    return find(workerId).orElseThrow();
  }

  /**
   * Records a worker's new state and the time of its last heartbeat.
   *
   * @param workerId the worker's ID
   * @param state its new state
   * @param lastHeartbeatAt when the controller last heard from it, in epoch milliseconds
   */
  public void updateState(String workerId, WorkerState state, long lastHeartbeatAt) {
    jdbc.sql(
            "UPDATE workers SET state = :state, last_heartbeat_at = :lastHeartbeatAt"
                + " WHERE worker_id = :workerId")
        .param("state", state.name())
        .param("lastHeartbeatAt", lastHeartbeatAt)
        .param("workerId", workerId)
        .update();
  }

  /**
   * Marks every worker SUSPECT. A restarted controller has no recent heartbeats, so it trusts no
   * worker until it hears from it again.
   *
   * @return how many workers were marked
   */
  public int markAllSuspect() {
    return jdbc.sql("UPDATE workers SET state = 'SUSPECT'").update();
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
