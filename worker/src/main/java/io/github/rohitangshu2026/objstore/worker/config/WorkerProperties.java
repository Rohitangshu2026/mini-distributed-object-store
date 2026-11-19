package io.github.rohitangshu2026.objstore.worker.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The worker's settings, bound from the environment through {@code application.yml}.
 *
 * @param workerId this worker's ID ({@code WORKER_ID}), for example {@code worker2}
 * @param dataDir the directory on the worker's volume that holds its files ({@code DATA_DIR})
 * @param controllerUrl the controller's base URL ({@code CONTROLLER_URL})
 * @param advertiseUrl the URL the controller should call this worker on ({@code
 *     WORKER_ADVERTISE_URL})
 * @param heartbeatInterval how often to send a heartbeat ({@code HEARTBEAT_INTERVAL_MS})
 */
@ConfigurationProperties("objstore")
public record WorkerProperties(
    String workerId,
    Path dataDir,
    URI controllerUrl,
    String advertiseUrl,
    Duration heartbeatInterval) {

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
    if (controllerUrl == null) {
      throw new IllegalArgumentException("CONTROLLER_URL must be set to the controller's URL");
    }
    if (advertiseUrl == null || advertiseUrl.isBlank()) {
      throw new IllegalArgumentException("WORKER_ADVERTISE_URL must be set to this worker's URL");
    }
    if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
      throw new IllegalArgumentException("HEARTBEAT_INTERVAL_MS must be positive");
    }
  }
}
