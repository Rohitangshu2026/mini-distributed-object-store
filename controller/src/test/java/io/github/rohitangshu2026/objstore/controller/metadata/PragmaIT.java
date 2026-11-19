package io.github.rohitangshu2026.objstore.controller.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.sqlite.SQLiteConnection;

/** Checks that the SQLite settings of {@code docs/architecture.md} §11 reach every connection. */
class PragmaIT extends SqliteTestBase {

  @Autowired DataSource dataSource;

  @Test
  void everyPooledConnectionHasTheSqliteSettings() throws SQLException {
    // Holding two connections at once makes the pool hand out two different ones.
    try (Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection()) {
      assertThat(first.unwrap(SQLiteConnection.class))
          .isNotSameAs(second.unwrap(SQLiteConnection.class));
      for (Connection connection : List.of(first, second)) {
        assertThat(pragma(connection, "foreign_keys")).isEqualTo("1");
        assertThat(pragma(connection, "journal_mode")).isEqualTo("wal");
        assertThat(pragma(connection, "synchronous")).isEqualTo("2");
        assertThat(pragma(connection, "busy_timeout")).isEqualTo("5000");
      }
    }
  }

  private static String pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      result.next();
      return result.getString(1);
    }
  }
}
