package io.github.rohitangshu2026.objstore.worker.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the startup checks in {@link WorkerProperties}. */
class WorkerPropertiesTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void blankWorkerIdIsRejected(String workerId) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties(workerId, Path.of("/data"), Duration.ofSeconds(3)))
        .withMessageContaining("WORKER_ID");
  }

  @Test
  void missingDataDirIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties("worker1", null, Duration.ofSeconds(3)))
        .withMessageContaining("DATA_DIR");
  }

  @Test
  void nonPositiveHeartbeatIntervalIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> properties("worker1", Path.of("/data"), Duration.ZERO))
        .withMessageContaining("HEARTBEAT_INTERVAL_MS");
  }

  private static WorkerProperties properties(String workerId, Path dataDir, Duration interval) {
    return new WorkerProperties(
        workerId, dataDir, URI.create("http://controller:8080"), "http://worker1:9000", interval);
  }
}
