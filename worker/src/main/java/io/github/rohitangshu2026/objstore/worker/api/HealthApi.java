package io.github.rohitangshu2026.objstore.worker.api;

import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.BootCounter;
import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/** Liveness for the Compose healthcheck, with the worker's identity and boot count. */
@RestController
class HealthApi {

  private final WorkerProperties properties;
  private final BootCounter bootCounter;
  private final String version;

  HealthApi(WorkerProperties properties, BootCounter bootCounter, Optional<BuildProperties> build) {
    this.properties = properties;
    this.bootCounter = bootCounter;
    // build-info.properties is written by the Maven build, so an IDE run may not have it.
    this.version = build.map(BuildProperties::getVersion).orElse("unknown");
  }

  @GetMapping("/health")
  Health health() {
    // No chunk store yet, so this worker holds no chunks.
    return new Health("UP", properties.workerId(), version, bootCounter.count(), 0);
  }

  @JsonNaming(SnakeCaseStrategy.class)
  record Health(String status, String workerId, String version, long bootCount, long chunkCount) {}
}
