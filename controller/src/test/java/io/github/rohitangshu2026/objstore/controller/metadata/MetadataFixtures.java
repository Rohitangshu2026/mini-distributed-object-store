package io.github.rohitangshu2026.objstore.controller.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.controller.ControllerApplication;
import java.util.Locale;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.sqlite.SQLiteException;

/**
 * Helpers for metadata tests: valid rows, starting the controller on a given database, and reading
 * SQLite's result code from a failure. Callers pass the IDs, so tests never share rows by accident.
 */
public final class MetadataFixtures {

  /** A fixed time in epoch milliseconds, 2025-11-18T21:46:40Z. */
  public static final long NOW = 1_763_502_400_000L;

  private MetadataFixtures() {}

  /** Returns a well-formed object ID, distinct for each {@code n}. */
  public static String objectId(int n) {
    return String.format(Locale.ROOT, "019a8f2e-0000-7000-8000-%012d", n);
  }

  /** Returns an object still being uploaded, so its size, checksum and chunk count are unknown. */
  public static ObjectRow uploading(String objectId) {
    return new ObjectRow(
        objectId,
        "photo.jpg",
        null,
        "image/jpeg",
        null,
        null,
        ObjectStatus.UPLOADING,
        NOW,
        NOW,
        "{}");
  }

  /** Returns a committed object with every total known. */
  public static ObjectRow committed(String objectId, int chunkCount) {
    return new ObjectRow(
        objectId,
        "report.pdf",
        2_500_000L,
        "application/pdf",
        checksum(objectId.hashCode()),
        chunkCount,
        ObjectStatus.COMMITTED,
        NOW,
        NOW,
        "{\"team\":\"storage\"}");
  }

  /** Returns the chunk of {@code objectId} at {@code index}. */
  public static ChunkRow chunk(String objectId, int index) {
    return new ChunkRow(
        objectId + "-" + String.format(Locale.ROOT, "%06d", index),
        objectId,
        index,
        1_000 + index,
        checksum(index));
  }

  /** Returns an ALIVE worker. */
  public static WorkerRow worker(String workerId) {
    return new WorkerRow(workerId, "http://" + workerId + ":9000", WorkerState.ALIVE, NOW, NOW);
  }

  /**
   * Starts the controller without a web server. The URL is a command-line argument because {@code
   * properties()} would set a default, which {@code application.yml} overrides.
   */
  public static ConfigurableApplicationContext startController(String url) {
    return new SpringApplicationBuilder(ControllerApplication.class)
        .web(WebApplicationType.NONE)
        .run("--spring.datasource.url=" + url);
  }

  /**
   * Returns the SQLite primary result code behind a failure, after checking that SQLite raised it,
   * so a test can tell a constraint or lock failure from a mistake in its own statement.
   */
  public static int sqliteResultCode(Throwable failure) {
    Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
    assertThat(cause).isInstanceOf(SQLiteException.class);
    // Extended result codes carry the primary code in their low byte.
    return ((SQLiteException) cause).getResultCode().code & 0xff;
  }

  /** Returns a checksum-shaped string: 64 lowercase hex characters, distinct for each {@code n}. */
  public static String checksum(int n) {
    return String.format(Locale.ROOT, "%064x", n & 0xffffffffL);
  }
}
