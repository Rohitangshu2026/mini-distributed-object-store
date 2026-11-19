package io.github.rohitangshu2026.objstore.controller.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

/**
 * Checks that a migration failing halfway leaves no partial version: SQLite runs DDL inside the
 * migration's transaction, so the failure rolls back everything the migration did.
 */
class FailedMigrationIT {

  @Test
  void failedMigrationLeavesNoTraceOfItself(@TempDir Path dir) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dir.resolve("controller.db"));
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:db/failing-migration")
            .load();

    assertThatExceptionOfType(FlywayException.class).isThrownBy(flyway::migrate);

    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertThat(
            jdbc.sql("SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")
                .query(String.class)
                .list())
        .contains("workers", "objects")
        .doesNotContain("half_done");
    assertThat(
            jdbc.sql("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL")
                .query(String.class)
                .list())
        .containsExactly("1");
  }
}
