package io.github.rohitangshu2026.objstore.controller.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.controller.api.HealthApi.Health;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

/** Tests that {@code /health} follows the metadata database, using real SQLite files. */
class HealthApiTest {

  @TempDir Path dataDir;

  @Test
  void reportsUpWhileTheDatabaseAnswers() {
    ResponseEntity<Health> response = healthApiOn(dataDir.resolve("controller.db")).health();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(new Health("UP", "controller", "unknown"));
  }

  @Test
  void reportsDownWithServiceUnavailableWhenTheDatabaseCannotBeOpened() {
    // SQLite cannot create a file inside a directory that does not exist.
    Path unopenable = dataDir.resolve("missing-dir").resolve("controller.db");

    ResponseEntity<Health> response = healthApiOn(unopenable).health();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEqualTo(new Health("DOWN", "controller", "unknown"));
  }

  private static HealthApi healthApiOn(Path database) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + database);
    return new HealthApi(JdbcClient.create(dataSource), Optional.empty());
  }
}
