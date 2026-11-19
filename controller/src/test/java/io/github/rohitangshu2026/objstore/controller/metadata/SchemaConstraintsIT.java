package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.sqliteResultCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteErrorCode;

/**
 * Checks that the database itself rejects every invalid row the V1 schema declares, one constraint
 * per case, and accepts the schema's deliberate allowances.
 */
class SchemaConstraintsIT extends SqliteTestBase {

  @Autowired JdbcClient jdbc;

  /** Valid rows for the invalid statements to collide with or refer to. */
  @BeforeEach
  void existingRows() {
    jdbc.sql("INSERT OR IGNORE INTO workers VALUES ('w1', 'http://w1:9000', 'ALIVE', 1, 1)")
        .update();
    jdbc.sql("INSERT OR IGNORE INTO workers VALUES ('w2', 'http://w2:9000', 'ALIVE', 1, 1)")
        .update();
    jdbc.sql(
            "INSERT OR IGNORE INTO objects (object_id, name, content_type, status, created_at,"
                + " updated_at) VALUES ('o1', 'a.bin', 'application/octet-stream', 'UPLOADING', 1,"
                + " 1)")
        .update();
    jdbc.sql("INSERT OR IGNORE INTO chunks VALUES ('o1-000000', 'o1', 0, 10, 'aa')").update();
    jdbc.sql("INSERT OR IGNORE INTO chunk_replicas VALUES ('o1-000000', 'w1', 'PENDING', NULL)")
        .update();
    jdbc.sql("INSERT OR IGNORE INTO tombstones VALUES ('o1-000000', 'w1', 'WRITE_FAILED', 1)")
        .update();
  }

  static Stream<Arguments> invalidRows() {
    return Stream.of(
        // workers (worker_id, url, state, last_heartbeat_at, registered_at)
        arguments(
            "a worker ID already used", "INSERT INTO workers VALUES ('w1', 'u', 'ALIVE', 1, 1)"),
        arguments("a NULL worker ID", "INSERT INTO workers VALUES (NULL, 'u', 'ALIVE', 1, 1)"),
        arguments(
            "an unknown worker state", "INSERT INTO workers VALUES ('w9', 'u', 'GONE', 1, 1)"),
        arguments(
            "a worker without a URL", "INSERT INTO workers VALUES ('w9', NULL, 'ALIVE', 1, 1)"),
        arguments(
            "a worker without a heartbeat time",
            "INSERT INTO workers VALUES ('w9', 'u', 'ALIVE', NULL, 1)"),
        arguments(
            "a worker without a registration time",
            "INSERT INTO workers VALUES ('w9', 'u', 'ALIVE', 1, NULL)"),
        arguments(
            "text in an INTEGER column of a STRICT table",
            "INSERT INTO workers VALUES ('w9', 'u', 'ALIVE', 'yesterday', 1)"),
        // objects (object_id, name, size, content_type, checksum_sha256, chunk_count, status,
        // created_at, updated_at, custom_metadata)
        arguments(
            "an object ID already used",
            "INSERT INTO objects VALUES ('o1', 'b', NULL, 't', NULL, NULL, 'UPLOADING', 1, 1, '{}')"),
        arguments(
            "a NULL object ID",
            "INSERT INTO objects VALUES (NULL, 'b', NULL, 't', NULL, NULL, 'UPLOADING', 1, 1, '{}')"),
        arguments(
            "an unknown object status",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, 't', NULL, NULL, 'ARCHIVED', 1, 1, '{}')"),
        arguments(
            "a COMMITTED object without a size",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, 't', 'cc', 1, 'COMMITTED', 1, 1, '{}')"),
        arguments(
            "a COMMITTED object without a checksum",
            "INSERT INTO objects VALUES ('o9', 'b', 5, 't', NULL, 1, 'COMMITTED', 1, 1, '{}')"),
        arguments(
            "a COMMITTED object without a chunk count",
            "INSERT INTO objects VALUES ('o9', 'b', 5, 't', 'cc', NULL, 'COMMITTED', 1, 1, '{}')"),
        arguments(
            "an object without a name",
            "INSERT INTO objects VALUES ('o9', NULL, NULL, 't', NULL, NULL, 'UPLOADING', 1, 1, '{}')"),
        arguments(
            "an object without a content type",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, NULL, NULL, NULL, 'UPLOADING', 1, 1, '{}')"),
        arguments(
            "an object without a creation time",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, 't', NULL, NULL, 'UPLOADING', NULL, 1,"
                + " '{}')"),
        arguments(
            "an object without an update time",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, 't', NULL, NULL, 'UPLOADING', 1, NULL,"
                + " '{}')"),
        arguments(
            "an object with NULL custom metadata",
            "INSERT INTO objects VALUES ('o9', 'b', NULL, 't', NULL, NULL, 'UPLOADING', 1, 1, NULL)"),
        // chunks (chunk_id, object_id, chunk_index, size, checksum_sha256)
        arguments(
            "a chunk of an unknown object",
            "INSERT INTO chunks VALUES ('c9', 'nope', 0, 10, 'aa')"),
        arguments(
            "a second chunk at the same index of one object",
            "INSERT INTO chunks VALUES ('c9', 'o1', 0, 10, 'aa')"),
        arguments(
            "a chunk ID already used",
            "INSERT INTO chunks VALUES ('o1-000000', 'o1', 5, 10, 'aa')"),
        arguments("a NULL chunk ID", "INSERT INTO chunks VALUES (NULL, 'o1', 5, 10, 'aa')"),
        arguments("a negative chunk index", "INSERT INTO chunks VALUES ('c9', 'o1', -1, 10, 'aa')"),
        arguments("an empty chunk", "INSERT INTO chunks VALUES ('c9', 'o1', 5, 0, 'aa')"),
        arguments(
            "a chunk without an object", "INSERT INTO chunks VALUES ('c9', NULL, 5, 10, 'aa')"),
        arguments(
            "a chunk without an index", "INSERT INTO chunks VALUES ('c9', 'o1', NULL, 10, 'aa')"),
        arguments(
            "a chunk without a size", "INSERT INTO chunks VALUES ('c9', 'o1', 5, NULL, 'aa')"),
        arguments(
            "a chunk without a checksum", "INSERT INTO chunks VALUES ('c9', 'o1', 5, 10, NULL)"),
        // chunk_replicas (chunk_id, worker_id, status, last_verified_at)
        arguments(
            "a copy of an unknown chunk",
            "INSERT INTO chunk_replicas VALUES ('nope', 'w2', 'PENDING', NULL)"),
        arguments(
            "a copy on an unknown worker",
            "INSERT INTO chunk_replicas VALUES ('o1-000000', 'w9', 'PENDING', NULL)"),
        arguments(
            "a second copy of a chunk on the same worker (I4)",
            "INSERT INTO chunk_replicas VALUES ('o1-000000', 'w1', 'HEALTHY', 1)"),
        arguments(
            "an unknown replica status",
            "INSERT INTO chunk_replicas VALUES ('o1-000000', 'w2', 'BROKEN', NULL)"),
        arguments(
            "a copy without a status",
            "INSERT INTO chunk_replicas VALUES ('o1-000000', 'w2', NULL, NULL)"),
        arguments(
            "a copy without a chunk ID",
            "INSERT INTO chunk_replicas VALUES (NULL, 'w2', 'PENDING', NULL)"),
        arguments(
            "a copy without a worker ID",
            "INSERT INTO chunk_replicas VALUES ('o1-000000', NULL, 'PENDING', NULL)"),
        // tombstones (chunk_id, worker_id, reason, created_at)
        arguments(
            "a tombstone on an unknown worker",
            "INSERT INTO tombstones VALUES ('c9', 'w9', 'ORPHAN', 1)"),
        arguments(
            "a second tombstone for the same copy",
            "INSERT INTO tombstones VALUES ('o1-000000', 'w1', 'ORPHAN', 1)"),
        arguments(
            "an unknown tombstone reason",
            "INSERT INTO tombstones VALUES ('c9', 'w2', 'BORED', 1)"),
        arguments(
            "a tombstone without a reason", "INSERT INTO tombstones VALUES ('c9', 'w2', NULL, 1)"),
        arguments(
            "a tombstone without a time",
            "INSERT INTO tombstones VALUES ('c9', 'w2', 'ORPHAN', NULL)"),
        arguments(
            "a tombstone without a chunk ID",
            "INSERT INTO tombstones VALUES (NULL, 'w2', 'ORPHAN', 1)"),
        arguments(
            "a tombstone without a worker ID",
            "INSERT INTO tombstones VALUES ('c9', NULL, 'ORPHAN', 1)"));
  }

  @ParameterizedTest(name = "rejects {0}")
  @MethodSource("invalidRows")
  void databaseRejects(String description, String sql) {
    assertThatExceptionOfType(DataAccessException.class)
        .isThrownBy(() -> jdbc.sql(sql).update())
        .satisfies(e -> {
          // The rejection must come from a constraint, not from a mistake in the statement.
          assertThat(sqliteResultCode(e))
              .as("SQLite primary result code")
              .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT.code);
        });
  }

  @Test
  void tombstoneNeedsNoChunkRowBecauseItOutlivesTheChunk() {
    jdbc.sql("INSERT INTO tombstones VALUES ('gone-000000', 'w2', 'OBJECT_DELETED', 1)").update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM tombstones WHERE chunk_id = 'gone-000000'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void deletingAnObjectDeletesItsChunksAndTheirCopies() {
    jdbc.sql(
            "INSERT INTO objects (object_id, name, content_type, status, created_at, updated_at)"
                + " VALUES ('o3', 'c.bin', 'application/octet-stream', 'DELETING', 1, 1)")
        .update();
    jdbc.sql("INSERT INTO chunks VALUES ('o3-000000', 'o3', 0, 10, 'aa')").update();
    jdbc.sql("INSERT INTO chunk_replicas VALUES ('o3-000000', 'w2', 'HEALTHY', 1)").update();

    jdbc.sql("DELETE FROM objects WHERE object_id = 'o3'").update();

    assertThat(count("chunks WHERE object_id = 'o3'")).isZero();
    assertThat(count("chunk_replicas WHERE chunk_id = 'o3-000000'")).isZero();
  }

  @Test
  void customMetadataDefaultsToAnEmptyJsonObject() {
    jdbc.sql(
            "INSERT INTO objects (object_id, name, content_type, status, created_at, updated_at)"
                + " VALUES ('o4', 'd.bin', 'application/octet-stream', 'UPLOADING', 1, 1)")
        .update();

    assertThat(
            jdbc.sql("SELECT custom_metadata FROM objects WHERE object_id = 'o4'")
                .query(String.class)
                .single())
        .isEqualTo("{}");
  }

  private int count(String fromWhere) {
    return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Integer.class).single();
  }
}
