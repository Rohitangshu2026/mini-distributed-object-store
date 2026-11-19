package io.github.rohitangshu2026.objstore.controller.api;

import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness for the Compose healthcheck. */
@RestController
class HealthApi {

  private final String version;

  HealthApi(Optional<BuildProperties> build) {
    // build-info.properties is written by the Maven build, so an IDE run may not have it.
    this.version = build.map(BuildProperties::getVersion).orElse("unknown");
  }

  @GetMapping("/health")
  Health health() {
    return new Health("UP", "controller", version);
  }

  record Health(String status, String service, String version) {}
}
