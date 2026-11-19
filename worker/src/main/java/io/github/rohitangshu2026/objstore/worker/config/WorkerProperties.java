package io.github.rohitangshu2026.objstore.worker.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The worker's settings, bound from the environment through {@code application.yml}.
 *
 * @param workerId this worker's ID ({@code WORKER_ID}), for example {@code worker2}
 * @param dataDir the directory on the worker's volume that holds its files ({@code DATA_DIR})
 */
@ConfigurationProperties("objstore")
public record WorkerProperties(String workerId, Path dataDir) {

  /**
   * Checks the settings as Spring binds them.
   *
   * @throws IllegalArgumentException if a setting is missing or blank, which stops startup
   */
  public WorkerProperties {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("WORKER_ID must be set to this worker's ID");
    }
    if (dataDir == null) {
      throw new IllegalArgumentException("DATA_DIR must be set to the worker's data directory");
    }
  }
}
