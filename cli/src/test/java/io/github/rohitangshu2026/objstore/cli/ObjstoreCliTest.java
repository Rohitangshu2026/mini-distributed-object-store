package io.github.rohitangshu2026.objstore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ObjstoreCliTest {

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  @Test
  void versionPrintsTheBuildVersion() {
    assertThat(run("--version")).isZero();
    assertThat(out.toString()).matches("objstore \\d+\\.\\d+\\.\\d+\\S*\\R");
  }

  @Test
  void helpListsTheOptions() {
    assertThat(run("--help")).isZero();
    assertThat(out.toString()).contains("Usage: objstore", "--version");
  }

  @Test
  void noCommandIsAUsageError() {
    assertThat(run()).isEqualTo(CommandLine.ExitCode.USAGE);
    assertThat(err.toString()).contains("Missing command", "Usage: objstore");
  }

  private int run(String... args) {
    return new CommandLine(new ObjstoreCli())
        .setOut(new PrintWriter(out))
        .setErr(new PrintWriter(err))
        .execute(args);
  }
}
