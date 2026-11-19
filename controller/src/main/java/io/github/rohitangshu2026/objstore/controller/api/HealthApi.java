package io.github.rohitangshu2026.objstore.controller.api;

import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports the controller's liveness for the Compose healthcheck: {@code GET /health}. */
@RestController
class HealthApi {

  private final String version;

  HealthApi(Optional<BuildProperties> build) {
    // build-info.properties is written by the Maven build, so an IDE run may not have it.
    this.version = build.map(BuildProperties::getVersion).orElse("unknown");
  }

  /**
   * Returns the controller's health, which is {@code UP} whenever the controller can answer.
   *
   * @return the health report
   */
  @GetMapping("/health")
  Health health() {
    return new Health("UP", "controller", version);
  }

  /**
   * The {@code /health} response.
   *
   * @param status always {@code UP} while the controller answers
   * @param service always {@code controller}
   * @param version the build version, or {@code unknown} outside a Maven build
   */
  record Health(String status, String service, String version) {}
}
