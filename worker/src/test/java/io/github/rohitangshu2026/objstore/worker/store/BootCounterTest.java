package io.github.rohitangshu2026.objstore.worker.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BootCounterTest {

  @TempDir Path dataDir;

  @Test
  void firstBootIsOneAndEachStartAddsOne() throws IOException {
    assertThat(start().count()).isEqualTo(1);
    assertThat(start().count()).isEqualTo(2);
    assertThat(dataDir.resolve("boot_count")).hasContent("2");
  }

  @Test
  void leftoverTempFileDoesNotChangeTheCount() throws IOException {
    Files.writeString(dataDir.resolve("boot_count"), "3");
    Files.writeString(dataDir.resolve("boot_count.tmp"), "99");

    assertThat(start().count()).isEqualTo(4);
    assertThat(dataDir.resolve("boot_count.tmp")).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(strings = {"seven", "", "-1"})
  void invalidCountStopsStartupAndIsNeverReset(String content) throws IOException {
    Path file = Files.writeString(dataDir.resolve("boot_count"), content);

    assertThatIllegalStateException().isThrownBy(this::start).withMessageContaining("boot_count");
    assertThat(file).hasContent(content);
  }

  @Test
  void unreadableCountStopsStartup() throws IOException {
    Path file = Files.writeString(dataDir.resolve("boot_count"), "3");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    try {
      assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(this::start);
    } finally {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void unwritableDataDirStopsStartup() throws IOException {
    Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("r-x------"));
    try {
      assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(this::start);
    } finally {
      Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private BootCounter start() {
    return new BootCounter(new WorkerProperties("worker1", dataDir));
  }
}
