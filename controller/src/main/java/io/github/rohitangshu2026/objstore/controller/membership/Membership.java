package io.github.rohitangshu2026.objstore.controller.membership;

import io.github.rohitangshu2026.objstore.common.api.HeartbeatRequest;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.config.ControllerProperties;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.repair.ReplicaDegraded;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tracks which workers are ALIVE, SUSPECT or DEAD ({@code docs/architecture.md} §8).
 *
 * <p>Heartbeats only record the time they arrived, in memory, so they never wait on SQLite. Once a
 * second {@link #runOnce()} compares each worker's heartbeat age, by the controller's own clock,
 * with the SUSPECT and DEAD thresholds and persists any change. It runs on the single {@code
 * membershipScheduler} thread, which is the only code that changes a worker's state.
 */
@Component
public class Membership implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(Membership.class);

  /**
   * What the controller last heard from a worker.
   *
   * @param atMillis when, by the controller's clock
   * @param chunkCount the worker's reported chunk count, or {@code null} before its first heartbeat
   * @param usedBytes the worker's reported bytes used, or {@code null} before its first heartbeat
   * @param heard {@code false} if the worker has not been heard from since this controller started,
   *     in which case {@code atMillis} is the startup time
   */
  public record LastSeen(long atMillis, Long chunkCount, Long usedBytes, boolean heard) {}

  private final ConcurrentHashMap<String, LastSeen> lastSeen = new ConcurrentHashMap<>();
  private final WorkerRepository workers;
  private final ReplicaRepository replicas;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final long suspectAfterMillis;
  private final long deadAfterMillis;

  /**
   * Creates the membership tracker.
   *
   * @param workers the registered workers
   * @param replicas the chunk copies, marked LOST when their worker dies
   * @param transactions the transactions that persist each change
   * @param events where state changes are published
   * @param clock the source of heartbeat times and ages
   * @param properties the heartbeat interval and thresholds
   */
  public Membership(
      WorkerRepository workers,
      ReplicaRepository replicas,
      TransactionTemplate transactions,
      ApplicationEventPublisher events,
      Clock clock,
      ControllerProperties properties) {
    this.workers = workers;
    this.replicas = replicas;
    this.transactions = transactions;
    this.events = events;
    this.clock = clock;
    this.suspectAfterMillis = properties.suspectAfter().toMillis();
    this.deadAfterMillis = properties.deadAfter().toMillis();
  }

  /** Runs {@link #startUp()} once the metadata store is ready, before any request is served. */
  @Override
  public void afterSingletonsInstantiated() {
    startUp();
  }

  /**
   * Starts tracking after a controller restart. No heartbeat survived the restart, so every worker
   * becomes SUSPECT and gets a full DEAD threshold, counted from now, to send one.
   */
  public void startUp() {
    long now = clock.millis();
    int marked = transactions.execute(status -> workers.markAllSuspect());
    workers
        .findAll()
        .forEach(w -> lastSeen.putIfAbsent(w.workerId(), new LastSeen(now, null, null, false)));
    log.atInfo()
        .addKeyValue("operation", "membership_startup")
        .log("Marked {} registered workers SUSPECT until they send a heartbeat", marked);
  }

  /**
   * Registers a worker, or updates its URL, and counts the registration as a heartbeat. The worker
   * becomes ALIVE at the next check.
   *
   * @param workerId the worker's ID
   * @param url the URL to call it on
   * @return the worker as stored
   */
  public WorkerRow register(String workerId, String url) {
    long now = clock.millis();
    WorkerRow worker = transactions.execute(status -> workers.register(workerId, url, now));
    lastSeen.put(workerId, new LastSeen(now, null, null, true));
    log.atInfo()
        .addKeyValue("operation", "register")
        .addKeyValue("worker_id", workerId)
        .log("Worker registered at {}", url);
    events.publishEvent(new WorkerRegistered(workerId));
    return worker;
  }

  /**
   * Records a heartbeat, and marks the copies the worker reports as quarantined CORRUPT.
   *
   * @param heartbeat the heartbeat
   * @throws ApiException {@link ErrorCode#WORKER_NOT_FOUND} if the worker has not registered since
   *     this controller started, which tells it to register again
   */
  public void heartbeat(HeartbeatRequest heartbeat) {
    String workerId = heartbeat.workerId();
    LastSeen seen =
        new LastSeen(clock.millis(), heartbeat.chunkCount(), heartbeat.usedBytes(), true);
    if (lastSeen.computeIfPresent(workerId, (id, previous) -> seen) == null) {
      throw new ApiException(ErrorCode.WORKER_NOT_FOUND, "Worker is not registered");
    }
    List<String> corrupt = Optional.ofNullable(heartbeat.corruptChunks()).orElse(List.of());
    if (!corrupt.isEmpty()) {
      List<String> marked =
          transactions.execute(status ->
              corrupt.stream().filter(chunkId -> replicas.markCorrupt(chunkId, workerId)).toList());
      // After the commit, so repair never reads a mark that could still roll back.
      for (String chunkId : marked) {
        log.atWarn()
            .addKeyValue("operation", "heartbeat")
            .addKeyValue("worker_id", workerId)
            .addKeyValue("chunk_id", chunkId)
            .log("Worker quarantined a corrupt copy; marked it CORRUPT");
        events.publishEvent(new ReplicaDegraded(chunkId, workerId));
      }
    }
  }

  /**
   * {@return what the controller last heard from a worker, or empty if nothing since it started}
   *
   * @param workerId the worker's ID
   */
  public Optional<LastSeen> lastSeen(String workerId) {
    return Optional.ofNullable(lastSeen.get(workerId));
  }

  /** Runs the membership check every second on the {@code membershipScheduler} thread. */
  @Scheduled(fixedDelay = 1000, scheduler = "membershipScheduler")
  void scheduledCheck() {
    runOnce();
  }

  /**
   * Compares every worker's heartbeat age with the thresholds and persists each change. A change
   * that fails to persist is logged and left for the next run; the stored state stays as it was.
   */
  public void runOnce() {
    long now = clock.millis();
    for (WorkerRow worker : workers.findAll()) {
      LastSeen seen = lastSeen.get(worker.workerId());
      if (seen == null) {
        continue;
      }
      WorkerState next = nextState(worker.state(), now - seen.atMillis(), seen.heard());
      if (next != worker.state()) {
        transition(worker, next, seen.atMillis());
      }
    }
  }

  /**
   * Decides a worker's state from its heartbeat age. A DEAD worker comes back only on a fresh
   * heartbeat, never through SUSPECT, and no worker becomes ALIVE before it has been heard from.
   */
  private WorkerState nextState(WorkerState current, long ageMillis, boolean heard) {
    if (ageMillis >= deadAfterMillis) {
      return WorkerState.DEAD;
    }
    if (ageMillis >= suspectAfterMillis) {
      return current == WorkerState.DEAD ? WorkerState.DEAD : WorkerState.SUSPECT;
    }
    return heard ? WorkerState.ALIVE : current;
  }

  /**
   * Persists one transition. A worker going DEAD has its HEALTHY copies marked LOST in the same
   * transaction, so the two never disagree after a crash.
   */
  private void transition(WorkerRow worker, WorkerState next, long lastHeartbeatAt) {
    String workerId = worker.workerId();
    int lost;
    try {
      lost =
          transactions.execute(status -> {
            workers.updateState(workerId, next, lastHeartbeatAt);
            return next == WorkerState.DEAD ? replicas.markLostOn(workerId) : 0;
          });
    } catch (DataAccessException | TransactionException e) {
      log.atError()
          .setCause(e)
          .addKeyValue("operation", "membership_check")
          .addKeyValue("worker_id", workerId)
          .log("Could not persist {} -> {}; the next check tries again", worker.state(), next);
      return;
    }
    // A worker that stops answering degrades service, so losing one is a warning.
    LoggingEventBuilder line = next == WorkerState.ALIVE ? log.atInfo() : log.atWarn();
    line.addKeyValue("operation", "membership_check")
        .addKeyValue("worker_id", workerId)
        .log(
            "Worker {} -> {}{}",
            worker.state(),
            next,
            lost > 0 ? "; marked " + lost + " copies LOST" : "");
    events.publishEvent(new WorkerStateChanged(workerId, worker.state(), next));
  }
}
