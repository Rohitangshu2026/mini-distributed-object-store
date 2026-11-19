package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.sqliteResultCode;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.startController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

/** Checks that the controller refuses to start when it cannot open its metadata database. */
class DatabaseStartupIT {

  @Test
  void startupFailsWhenTheDatabaseFileCannotBeOpened(@TempDir Path dir) {
    String url = "jdbc:sqlite:" + dir.resolve("no-such-directory/controller.db");

    Throwable failure = catchThrowable(() -> startController(url).close());

    assertThat(sqliteResultCode(failure)).isEqualTo(SQLiteErrorCode.SQLITE_CANTOPEN.code);
  }
}
