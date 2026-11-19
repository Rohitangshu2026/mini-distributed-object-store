package io.github.rohitangshu2026.objstore.controller.membership;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.committed;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.rohitangshu2026.objstore.common.MutableClock;
import io.github.rohitangshu2026.objstore.common.api.HeartbeatRequest;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.config.ControllerProperties;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.SqliteTestBase;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks worker membership against a real SQLite file, moving a {@link MutableClock} instead of
 * sleeping. Default thresholds: a 3 s interval, SUSPECT after 9 s and DEAD after 15 s.
 */
class MembershipIT extends SqliteTestBase {

  private static final ControllerProperties DEFAULTS =
      new ControllerProperties(Duration.ofSeconds(3), 3, 5, 4);

  @Autowired JdbcClient jdbc;
  @Autowired WorkerRepository workers;
  @Autowired ReplicaRepository replicas;
  @Autowired ObjectRepository objects;
  @Autowired PlatformTransactionManager transactionManager;

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(NOW));
  private final List<Object> events = new ArrayList<>();
  private Membership membership;

  @BeforeEach
  void emptyDatabase() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
    membership = membership(DEFAULTS, new TransactionTemplate(transactionManager));
  }

  @Test
  void registeredWorkerStartsSuspectAndIsAliveAtTheNextCheck() {
    membership.register("worker1", "http://worker1:9000");
    assertThat(state("worker1")).isEqualTo(WorkerState.SUSPECT);

    membership.runOnce();

    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);
    assertThat(events)
        .containsExactly(new WorkerStateChanged("worker1", WorkerState.SUSPECT, WorkerState.ALIVE));
  }

  @Test
  void steadyHeartbeatsKeepAWorkerAlive() {
    aliveWorker("worker1");

    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofSeconds(3));
      membership.heartbeat(heartbeat("worker1", List.of()));
      membership.runOnce();
    }

    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);
  }

  @Test
  void heartbeatAgeDecidesSuspectAndDeadAtExactlyTheThresholds() {
    aliveWorker("worker1");

    advanceAndCheck(Duration.ofMillis(8_999));
    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);
    advanceAndCheck(Duration.ofMillis(1));
    assertThat(state("worker1")).as("age exactly 9 s").isEqualTo(WorkerState.SUSPECT);
    advanceAndCheck(Duration.ofMillis(5_999));
    assertThat(state("worker1")).isEqualTo(WorkerState.SUSPECT);
    advanceAndCheck(Duration.ofMillis(1));
    assertThat(state("worker1")).as("age exactly 15 s").isEqualTo(WorkerState.DEAD);
  }

  @Test
  void customThresholdsAreHonoured() {
    membership =
        membership(
            new ControllerProperties(Duration.ofSeconds(1), 2, 4, 4),
            new TransactionTemplate(transactionManager));
    aliveWorker("worker1");

    advanceAndCheck(Duration.ofSeconds(2));
    assertThat(state("worker1")).isEqualTo(WorkerState.SUSPECT);
    advanceAndCheck(Duration.ofSeconds(2));
    assertThat(state("worker1")).isEqualTo(WorkerState.DEAD);
  }

  @Test
  void deadThresholdMustExceedTheSuspectThreshold() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ControllerProperties(Duration.ofSeconds(3), 5, 5, 4));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ControllerProperties(Duration.ZERO, 3, 5, 4));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ControllerProperties(Duration.ofSeconds(3), 3, 5, 0));
  }

  @Test
  void goingDeadMarksOnlyItsHealthyCopiesLost() {
    aliveWorker("worker1");
    aliveWorker("worker2");
    String id = objectId(1);
    objects.insert(committed(id, 2));
    objects.insertChunk(chunk(id, 0));
    objects.insertChunk(chunk(id, 1));
    replicas.insert(new ReplicaRow(id + "-000000", "worker1", ReplicaStatus.HEALTHY, NOW));
    replicas.insert(new ReplicaRow(id + "-000000", "worker2", ReplicaStatus.HEALTHY, NOW));
    replicas.insert(new ReplicaRow(id + "-000001", "worker1", ReplicaStatus.CORRUPT, NOW));

    clock.advance(Duration.ofSeconds(15));
    membership.heartbeat(heartbeat("worker2", List.of()));
    membership.runOnce();

    assertThat(state("worker1")).isEqualTo(WorkerState.DEAD);
    assertThat(replicaStatus(id + "-000000", "worker1")).isEqualTo(ReplicaStatus.LOST);
    assertThat(replicaStatus(id + "-000000", "worker2")).isEqualTo(ReplicaStatus.HEALTHY);
    assertThat(replicaStatus(id + "-000001", "worker1")).isEqualTo(ReplicaStatus.CORRUPT);
  }

  @Test
  void deadWorkerComesBackAliveOnAFreshHeartbeatButNeverThroughSuspect() {
    aliveWorker("worker1");
    advanceAndCheck(Duration.ofSeconds(15));
    assertThat(state("worker1")).isEqualTo(WorkerState.DEAD);

    membership.register("worker1", "http://worker1:9000");
    membership.runOnce();

    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);
    assertThat(events)
        .extracting(e -> ((WorkerStateChanged) e).to())
        .containsExactly(WorkerState.ALIVE, WorkerState.DEAD, WorkerState.ALIVE);
  }

  @Test
  void heartbeatFromAnUnknownWorkerTellsItToRegister() {
    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(() -> membership.heartbeat(heartbeat("worker9", List.of())))
        .extracting(ApiException::code)
        .isEqualTo(ErrorCode.WORKER_NOT_FOUND);
  }

  @Test
  void reportedCorruptCopiesAreMarkedCorruptOnlyIfHealthy() {
    aliveWorker("worker1");
    String id = objectId(2);
    objects.insert(committed(id, 2));
    objects.insertChunk(chunk(id, 0));
    objects.insertChunk(chunk(id, 1));
    replicas.insert(new ReplicaRow(id + "-000000", "worker1", ReplicaStatus.HEALTHY, NOW));
    replicas.insert(new ReplicaRow(id + "-000001", "worker1", ReplicaStatus.LOST, NOW));

    membership.heartbeat(
        heartbeat("worker1", List.of(id + "-000000", id + "-000001", id + "-000009")));

    assertThat(replicaStatus(id + "-000000", "worker1")).isEqualTo(ReplicaStatus.CORRUPT);
    assertThat(replicaStatus(id + "-000001", "worker1")).isEqualTo(ReplicaStatus.LOST);
  }

  @Test
  void restartedControllerMarksEveryWorkerSuspectThenAliveAfterOneHeartbeat() {
    aliveWorker("worker1");
    aliveWorker("worker2");

    Membership restarted = membership(DEFAULTS, new TransactionTemplate(transactionManager));
    restarted.startUp();
    assertThat(state("worker1")).isEqualTo(WorkerState.SUSPECT);
    assertThat(state("worker2")).isEqualTo(WorkerState.SUSPECT);

    clock.advance(Duration.ofSeconds(3));
    restarted.heartbeat(heartbeat("worker1", List.of()));
    restarted.runOnce();
    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);
    assertThat(state("worker2")).as("no heartbeat yet").isEqualTo(WorkerState.SUSPECT);

    clock.advance(Duration.ofSeconds(12));
    restarted.runOnce();
    assertThat(state("worker2")).as("15 s after the restart").isEqualTo(WorkerState.DEAD);
  }

  @Test
  void failedTransitionLeavesTheStoredStateAndIsRetriedByTheNextCheck() {
    FailingTransactions transactions = new FailingTransactions(transactionManager);
    membership = membership(DEFAULTS, transactions);
    aliveWorker("worker1");
    clock.advance(Duration.ofSeconds(9));

    transactions.failNext = true;
    membership.runOnce();
    assertThat(state("worker1")).isEqualTo(WorkerState.ALIVE);

    membership.runOnce();
    assertThat(state("worker1")).isEqualTo(WorkerState.SUSPECT);
  }

  private Membership membership(ControllerProperties properties, TransactionTemplate transactions) {
    return new Membership(workers, replicas, transactions, events::add, clock, properties);
  }

  /** Registers a worker and runs a check, so it is ALIVE with a heartbeat at the current time. */
  private void aliveWorker(String workerId) {
    membership.register(workerId, "http://" + workerId + ":9000");
    membership.runOnce();
  }

  private void advanceAndCheck(Duration amount) {
    clock.advance(amount);
    membership.runOnce();
  }

  private WorkerState state(String workerId) {
    return workers.find(workerId).orElseThrow().state();
  }

  private ReplicaStatus replicaStatus(String chunkId, String workerId) {
    return replicas.replicasOf(chunkId).stream()
        .filter(replica -> replica.workerId().equals(workerId))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static HeartbeatRequest heartbeat(String workerId, List<String> corrupt) {
    return new HeartbeatRequest(workerId, 0, 0, corrupt);
  }

  /** A transaction template whose next transaction fails to begin, as SQLITE_BUSY would. */
  private static final class FailingTransactions extends TransactionTemplate {

    private static final long serialVersionUID = 1L;

    boolean failNext;

    FailingTransactions(PlatformTransactionManager transactionManager) {
      super(transactionManager);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      if (failNext) {
        failNext = false;
        throw new CannotCreateTransactionException("Simulated: database is locked");
      }
      return super.execute(action);
    }
  }
}
