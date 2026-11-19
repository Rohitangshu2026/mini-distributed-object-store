package io.github.rohitangshu2026.objstore.controller.metadata;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests against a real SQLite file. Each test class gets its own empty database in a temp
 * directory, which Flyway migrates when the application context starts.
 *
 * <p>The context is closed after each class, so a cached context never outlives the temp directory
 * that holds its database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@DirtiesContext
abstract class SqliteTestBase {

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SqliteTestBase::databaseUrl);
  }

  /** Returns the JDBC URL of this test class's database file. */
  static String databaseUrl() {
    return "jdbc:sqlite:" + dataDir.resolve("controller.db");
  }
}
