package io.github.rohitangshu2026.objstore.worker.api;

import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.BootCounter;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Reports the worker's liveness for the Compose healthcheck, {@code GET /health}, along with its
 * identity, version, boot count and chunk count.
 */
@RestController
class HealthApi {

  private final WorkerProperties properties;
  private final BootCounter bootCounter;
  private final ChunkStore store;
  private final String version;

  HealthApi(
      WorkerProperties properties,
      BootCounter bootCounter,
      ChunkStore store,
      Optional<BuildProperties> build) {
    this.properties = properties;
    this.bootCounter = bootCounter;
    this.store = store;
    // build-info.properties is written by the Maven build, so an IDE run may not have it.
    this.version = build.map(BuildProperties::getVersion).orElse("unknown");
  }

  /**
   * Returns the worker's health, which is {@code UP} whenever the worker can answer.
   *
   * @return the health report
   */
  @GetMapping("/health")
  Health health() {
    return new Health(
        "UP", properties.workerId(), version, bootCounter.count(), store.chunkCount());
  }

  /**
   * The {@code /health} response, serialized in snake_case.
   *
   * @param status always {@code UP} while the worker answers
   * @param workerId this worker's ID
   * @param version the build version, or {@code unknown} outside a Maven build
   * @param bootCount how many times this worker has started, including this time
   * @param chunkCount how many servable chunks the worker holds
   */
  @JsonNaming(SnakeCaseStrategy.class)
  record Health(String status, String workerId, String version, long bootCount, long chunkCount) {}
}
