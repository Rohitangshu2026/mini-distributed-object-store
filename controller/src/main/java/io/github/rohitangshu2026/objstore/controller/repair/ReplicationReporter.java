package io.github.rohitangshu2026.objstore.controller.repair;

import io.github.rohitangshu2026.objstore.common.api.ReplicationReport;
import io.github.rohitangshu2026.objstore.controller.metadata.ChunkHealth;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaLocation;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicationCounts;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the replication report ({@code docs/api.md} §4). A chunk counts as healthy only with 2
 * HEALTHY copies on ALIVE workers, so a chunk whose copy is on a SUSPECT worker already shows as
 * under-replicated, and one with no usable copy is never reported healthy (invariant I3).
 */
@Component
public class ReplicationReporter {

  /** The copies every chunk should have. */
  public static final int REPLICATION_FACTOR = 2;

  private static final int MAX_LISTED = 1000;

  private final ReplicaRepository replicas;
  private final TombstoneRepository tombstones;

  /**
   * Creates the reporter.
   *
   * @param replicas the chunk copies
   * @param tombstones the pending deletes
   */
  public ReplicationReporter(ReplicaRepository replicas, TombstoneRepository tombstones) {
    this.replicas = replicas;
    this.tombstones = tombstones;
  }

  /** {@return the current report} */
  public ReplicationReport report() {
    ReplicationCounts counts = replicas.replicationCounts();
    List<ChunkHealth> notHealthy = replicas.underReplicated(MAX_LISTED + 1);
    List<ChunkHealth> listed = notHealthy.subList(0, Math.min(MAX_LISTED, notHealthy.size()));
    Map<String, List<ReplicationReport.Copy>> copies =
        replicas.locationsOfChunks(listed.stream().map(ChunkHealth::chunkId).toList()).stream()
            .collect(
                Collectors.groupingBy(
                    ReplicaLocation::chunkId,
                    Collectors.mapping(
                        copy ->
                            new ReplicationReport.Copy(
                                copy.workerId(), copy.status().name(), copy.workerState().name()),
                        Collectors.toList())));
    return new ReplicationReport(
        REPLICATION_FACTOR,
        counts.total(),
        counts.healthy(),
        counts.underReplicated(),
        counts.unavailable(),
        tombstones.count(),
        listed.stream()
            .map(chunk ->
                new ReplicationReport.Chunk(
                    chunk.chunkId(),
                    chunk.objectId(),
                    chunk.healthyCopies(),
                    copies.getOrDefault(chunk.chunkId(), List.of())))
            .toList(),
        notHealthy.size() > MAX_LISTED);
  }
}
