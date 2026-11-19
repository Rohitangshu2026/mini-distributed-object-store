package io.github.rohitangshu2026.objstore.controller.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Checks the schema that Flyway's V1 migration builds in a fresh database. */
class MigrationIT extends SqliteTestBase {

  @Autowired JdbcClient jdbc;

  @Test
  void flywayAppliesVersionOne() {
    Map<String, Object> applied =
        jdbc.sql("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL")
            .query()
            .singleRow();

    assertThat(applied).containsEntry("version", "1").containsEntry("success", 1);
  }

  @Test
  void everyTableIsStrict() {
    assertThat(
            jdbc.sql(
                    "SELECT name FROM pragma_table_list"
                        + " WHERE schema = 'main' AND strict = 1 ORDER BY name")
                .query(String.class)
                .list())
        .containsExactly("chunk_replicas", "chunks", "objects", "tombstones", "workers");
  }

  @Test
  void everyIndexExists() {
    assertThat(
            jdbc.sql(
                    "SELECT name FROM sqlite_schema"
                        + " WHERE type = 'index' AND name NOT LIKE 'sqlite_autoindex_%'"
                        + " AND tbl_name <> 'flyway_schema_history' ORDER BY name")
                .query(String.class)
                .list())
        .containsExactly("chunk_replicas_by_worker", "objects_by_status", "tombstones_by_worker");
  }
}
