package io.github.rohitangshu2026.objstore.controller.api;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the controller's health for the Compose healthcheck: {@code GET /health}. The controller
 * is useless without its metadata, so it is healthy only while SQLite answers.
 */
@RestController
class HealthApi {

  private static final Logger log = LoggerFactory.getLogger(HealthApi.class);

  private final JdbcClient jdbc;
  private final String version;

  HealthApi(JdbcClient jdbc, Optional<BuildProperties> build) {
    this.jdbc = jdbc;
    // build-info.properties is written by the Maven build, so an IDE run may not have it.
    this.version = build.map(BuildProperties::getVersion).orElse("unknown");
  }

  /**
   * Returns the controller's health after checking that the metadata database answers.
   *
   * @return 200 with status {@code UP}, or 503 with status {@code DOWN} if the database fails
   */
  @GetMapping("/health")
  ResponseEntity<Health> health() {
    try {
      jdbc.sql("SELECT 1").query(Integer.class).single();
    } catch (DataAccessException e) {
      // The healthcheck repeats every few seconds, so one line without the stack trace is enough.
      log.warn("Health check failed: metadata database did not answer: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new Health("DOWN", "controller", version));
    }
    return ResponseEntity.ok(new Health("UP", "controller", version));
  }

  /**
   * The {@code /health} response.
   *
   * @param status {@code UP} while the metadata database answers, otherwise {@code DOWN}
   * @param service always {@code controller}
   * @param version the build version, or {@code unknown} outside a Maven build
   */
  record Health(String status, String service, String version) {}
}
