package io.github.rohitangshu2026.objstore.controller.repair;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.controller.membership.WorkerStateChanged;
import io.github.rohitangshu2026.objstore.controller.metadata.ChunkHealth;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaLocation;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.placement.Placement;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restores chunks of committed objects to 2 HEALTHY copies on ALIVE workers ({@code
 * docs/architecture.md} §9, invariant I3).
 *
 * <p>Each run takes up to 100 under-replicated chunks and repairs them on {@code repairExecutor},
 * {@code REPAIR_CONCURRENCY} at a time. For each chunk it reads a source copy and checks it against
 * the metadata, then first rewrites LOST or CORRUPT copies on ALIVE workers in place, then places
 * new copies on workers that hold no row or tombstone for the chunk. Every new copy has a PENDING
 * row before it is written (rule N4) and becomes HEALTHY only once its worker confirms (rule N2). A
 * chunk that cannot be finished is left for the next run, which acts as the backoff.
 *
 * <p>Runs happen every 30 s on the single {@code repairScheduler} thread, so they never overlap,
 * and as soon as a worker goes DEAD or a copy degrades; triggers that arrive together collapse into
 * one run.
 */
@Component
public class RepairJob {

  private static final Logger log = LoggerFactory.getLogger(RepairJob.class);
  private static final int BATCH = 100;

  /** What happened to one chunk in a run. */
  enum Outcome {
    /** The chunk has 2 HEALTHY copies on ALIVE workers again. */
    REPAIRED,
    /** It still has 1; no destination took a copy this run. */
    UNDER_REPLICATED,
    /** It has no valid copy to repair from. */
    UNAVAILABLE,
    /** The repair failed unexpectedly; the next run tries again. */
    FAILED
  }

  private final ReplicaRepository replicas;
  private final TombstoneRepository tombstones;
  private final WorkerRepository workers;
  private final WorkerClient workerClient;
  private final AsyncTaskExecutor repairExecutor;
  private final ThreadPoolTaskScheduler repairScheduler;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final AtomicBoolean triggered = new AtomicBoolean();

  /**
   * Creates the job.
   *
   * @param replicas the chunk copies
   * @param tombstones the pending deletes, which placement avoids
   * @param workers the workers, to find the ALIVE ones
   * @param workerClient the client that reads sources and writes copies
   * @param repairExecutor the pool that repairs chunks in parallel
   * @param repairScheduler the single thread that runs sweeps
   * @param transactions the transactions around each metadata change
   * @param clock the source of timestamps
   */
  public RepairJob(
      ReplicaRepository replicas,
      TombstoneRepository tombstones,
      WorkerRepository workers,
      WorkerClient workerClient,
      @Qualifier("repairExecutor") AsyncTaskExecutor repairExecutor,
      @Qualifier("repairScheduler") ThreadPoolTaskScheduler repairScheduler,
      TransactionTemplate transactions,
      Clock clock) {
    this.replicas = replicas;
    this.tombstones = tombstones;
    this.workers = workers;
    this.workerClient = workerClient;
    this.repairExecutor = repairExecutor;
    this.repairScheduler = repairScheduler;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Sweeps every 30 s on the {@code repairScheduler} thread. */
  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000, scheduler = "repairScheduler")
  void scheduledRun() {
    runOnce();
  }

  /**
   * Starts a repair as soon as a worker goes DEAD.
   *
   * @param change the worker's state change
   */
  @EventListener
  public void on(WorkerStateChanged change) {
    if (change.to() == WorkerState.DEAD) {
      trigger();
    }
  }

  /**
   * Starts a repair as soon as a copy is marked CORRUPT or LOST.
   *
   * @param degraded the degraded copy
   */
  @EventListener
  public void on(ReplicaDegraded degraded) {
    trigger();
  }

  /** Schedules one extra run now; triggers that arrive before it starts share it. */
  public void trigger() {
    if (triggered.compareAndSet(false, true)) {
      repairScheduler.execute(() -> {
        triggered.set(false);
        runOnce();
      });
    }
  }

  /**
   * Repairs up to 100 under-replicated chunks, in parallel, and waits for them all.
   *
   * @return how many chunks ended the run with each outcome
   */
  public Map<Outcome, Integer> runOnce() {
    Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    List<ChunkHealth> chunks = replicas.underReplicated(BATCH);
    if (chunks.isEmpty()) {
      return outcomes;
    }
    List<WorkerRow> alive =
        workers.findAll().stream().filter(w -> w.state() == WorkerState.ALIVE).toList();
    // One load snapshot per run; placement only reads it.
    Map<String, Long> load = replicas.rowCountByWorker();
    List<Future<Outcome>> repairs = new ArrayList<>();
    for (ChunkHealth chunk : chunks) {
      repairs.add(repairExecutor.submit(() -> repair(chunk, alive, load)));
    }
    for (Future<Outcome> repair : repairs) {
      outcomes.merge(await(repair), 1, Integer::sum);
    }
    // One summary per run rather than a line per chunk.
    boolean allRepaired = outcomes.getOrDefault(Outcome.REPAIRED, 0) == chunks.size();
    (allRepaired ? log.atInfo() : log.atWarn())
        .addKeyValue("operation", "repair")
        .log("Repair run over {} under-replicated chunks: {}", chunks.size(), outcomes);
    return outcomes;
  }

  private static Outcome await(Future<Outcome> repair) {
    try {
      return repair.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.FAILED;
    } catch (ExecutionException e) {
      // repair() catches its own failures, so this is a bug; log it and let the next run retry.
      log.atError().setCause(e.getCause()).addKeyValue("operation", "repair").log("Repair failed");
      return Outcome.FAILED;
    }
  }

  /** Repairs one chunk; never throws. */
  private Outcome repair(ChunkHealth chunk, List<WorkerRow> alive, Map<String, Long> load) {
    try {
      return repairChunk(chunk, alive, load);
    } catch (RuntimeException e) {
      log.atError()
          .setCause(e)
          .addKeyValue("operation", "repair")
          .addKeyValue("chunk_id", chunk.chunkId())
          .log("Repair of the chunk failed; the next run tries again");
      return Outcome.FAILED;
    }
  }

  private Outcome repairChunk(ChunkHealth chunk, List<WorkerRow> alive, Map<String, Long> load) {
    Map<String, WorkerRow> aliveById =
        alive.stream().collect(Collectors.toMap(WorkerRow::workerId, Function.identity()));
    List<ReplicaLocation> copies = replicas.locationsOfChunks(List.of(chunk.chunkId()));
    List<ReplicaLocation> sources =
        copies.stream()
            .filter(c -> c.status() == ReplicaStatus.HEALTHY)
            .filter(c -> c.workerState() == WorkerState.ALIVE)
            .toList();
    byte[] bytes = readVerifiedSource(chunk, sources);
    if (bytes == null) {
      return Outcome.UNAVAILABLE;
    }
    int healthy = 1;

    // In place first: a LOST or CORRUPT copy on an ALIVE worker keeps its row and is rewritten.
    for (ReplicaLocation copy : copies) {
      boolean degraded =
          copy.status() == ReplicaStatus.LOST || copy.status() == ReplicaStatus.CORRUPT;
      if (healthy < 2 && degraded && aliveById.containsKey(copy.workerId())) {
        if (write(chunk, new WorkerRef(copy.workerId(), copy.url()), bytes)) {
          markHealthy(chunk, copy.workerId());
          healthy++;
        }
      }
    }

    // Then new copies, never on a worker that has a row or a tombstone for the chunk (I4).
    Set<String> excluded = new HashSet<>(tombstones.workersFor(chunk.chunkId()));
    copies.forEach(copy -> excluded.add(copy.workerId()));
    List<String> candidates = alive.stream().map(WorkerRow::workerId).sorted().toList();
    while (healthy < 2) {
      List<String> next = Placement.choose(candidates, load, chunk.chunkIndex(), excluded, 1);
      if (next.isEmpty()) {
        return Outcome.UNDER_REPLICATED;
      }
      String destination = next.get(0);
      excluded.add(destination);
      transactions.executeWithoutResult(status ->
          replicas.insert(
              new ReplicaRow(chunk.chunkId(), destination, ReplicaStatus.PENDING, null)));
      if (write(chunk, new WorkerRef(destination, aliveById.get(destination).url()), bytes)) {
        markHealthy(chunk, destination);
        healthy++;
      } else {
        long now = clock.millis();
        transactions.executeWithoutResult(status ->
            replicas.replaceWithTombstone(
                chunk.chunkId(), destination, TombstoneReason.WRITE_FAILED, now));
      }
    }
    return Outcome.REPAIRED;
  }

  /**
   * Reads the chunk from the first source whose bytes match the metadata. A source that does not is
   * marked CORRUPT, or LOST if its worker no longer has it.
   *
   * @return the verified bytes, or {@code null} if no source had them
   */
  private byte[] readVerifiedSource(ChunkHealth chunk, List<ReplicaLocation> sources) {
    for (ReplicaLocation source : sources) {
      try {
        byte[] bytes =
            workerClient.getChunk(new WorkerRef(source.workerId(), source.url()), chunk.chunkId());
        if (bytes.length == chunk.size()
            && Checksums.sha256Hex(bytes).equals(chunk.checksumSha256())) {
          return bytes;
        }
        mark(chunk, source, ReplicaStatus.CORRUPT);
      } catch (WorkerCallException e) {
        if (e.failure() == Failure.CORRUPT) {
          mark(chunk, source, ReplicaStatus.CORRUPT);
        } else if (e.failure() == Failure.NOT_FOUND) {
          mark(chunk, source, ReplicaStatus.LOST);
        }
        // Anything else is transient: try the next source and leave this one for the next run.
      }
    }
    return null;
  }

  private boolean write(ChunkHealth chunk, WorkerRef destination, byte[] bytes) {
    try {
      workerClient.putChunk(
          destination, chunk.chunkId(), bytes, bytes.length, chunk.checksumSha256());
      return true;
    } catch (WorkerCallException e) {
      log.atWarn()
          .addKeyValue("operation", "repair")
          .addKeyValue("chunk_id", chunk.chunkId())
          .addKeyValue("worker_id", destination.workerId())
          .log("Repair copy not confirmed ({})", e.failure());
      return false;
    }
  }

  private void markHealthy(ChunkHealth chunk, String workerId) {
    long now = clock.millis();
    transactions.executeWithoutResult(
        status -> replicas.markHealthy(chunk.chunkId(), workerId, now));
  }

  private void mark(ChunkHealth chunk, ReplicaLocation source, ReplicaStatus status) {
    transactions.executeWithoutResult(tx -> {
      if (status == ReplicaStatus.CORRUPT) {
        replicas.markCorrupt(chunk.chunkId(), source.workerId());
      } else {
        replicas.markLost(chunk.chunkId(), source.workerId());
      }
    });
    log.atWarn()
        .addKeyValue("operation", "repair")
        .addKeyValue("chunk_id", chunk.chunkId())
        .addKeyValue("worker_id", source.workerId())
        .log("Repair source failed verification; marked it {}", status);
  }
}
