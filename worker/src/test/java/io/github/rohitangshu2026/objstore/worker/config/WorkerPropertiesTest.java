package io.github.rohitangshu2026.objstore.worker.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
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
        .isThrownBy(() -> new WorkerProperties(workerId, Path.of("/data")))
        .withMessageContaining("WORKER_ID");
  }

  @Test
  void missingDataDirIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkerProperties("worker1", null))
        .withMessageContaining("DATA_DIR");
  }
}
