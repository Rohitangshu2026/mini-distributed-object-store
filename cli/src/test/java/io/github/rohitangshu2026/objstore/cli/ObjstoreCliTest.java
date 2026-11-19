package io.github.rohitangshu2026.objstore.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rohitangshu2026.objstore.common.Checksums;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests the {@code objstore} commands against a stub controller on a random port: what each sends,
 * what it prints, its exit code, and that a download is kept only if it is complete and correct.
 */
class ObjstoreCliTest {

  private static final String ID = "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f";

  @TempDir Path dir;

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> requestContentType = new AtomicReference<>();
  private HttpServer controller;

  @BeforeEach
  void startStubController() throws IOException {
    controller = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    controller.start();
  }

  @AfterEach
  void stopStubController() {
    controller.stop(0);
  }

  @Test
  void versionPrintsTheBuildVersion() {
    assertThat(run("--version")).isZero();
    assertThat(out.toString()).matches("objstore \\d+\\.\\d+\\.\\d+\\S*\\R");
  }

  @Test
  void helpListsTheCommands() {
    assertThat(run("--help")).isZero();
    assertThat(out.toString())
        .contains("Usage: objstore", "put", "get", "ls", "stat", "meta", "rm");
  }

  @Test
  void noCommandOrAMissingArgumentIsAUsageError() {
    assertThat(run()).isEqualTo(CommandLine.ExitCode.USAGE);
    assertThat(err.toString()).contains("Missing command");
    assertThat(run("--url", url(), "get")).isEqualTo(CommandLine.ExitCode.USAGE);
  }

  @Test
  void putSendsTheMetadataPartFirstThenTheFile() throws IOException {
    Path file = Files.writeString(dir.resolve("report.pdf"), "file bytes");
    respond("/api/v1/objects", 201, "{\"object_id\":\"" + ID + "\"}");

    int code = run("--url", url(), "put", file.toString(), "--meta", "team=cc");

    assertThat(code).isZero();
    assertThat(out.toString()).contains("\"object_id\" : \"" + ID + "\"");
    assertThat(requestContentType.get()).startsWith("multipart/form-data; boundary=objstore-");
    String body = requestBody.get();
    assertThat(body.indexOf("name=\"metadata\"")).isLessThan(body.indexOf("name=\"file\""));
    assertThat(body).contains("{\"team\":\"cc\"}", "filename=\"report.pdf\"", "file bytes");
  }

  @Test
  void getKeepsAFileOnlyWhenItsLengthAndChecksumMatch() throws IOException {
    byte[] content = "the object's bytes".getBytes(UTF_8);
    serve("/api/v1/objects/" + ID, content, Checksums.sha256Hex(content), content.length);
    Path saved = dir.resolve("out.bin");

    assertThat(run("--url", url(), "get", ID, "-o", saved.toString())).isZero();
    assertThat(Files.readAllBytes(saved)).isEqualTo(content);
    assertThat(err.toString()).contains("verified");
  }

  @Test
  void getWithTheWrongChecksumFailsAndKeepsNothing() {
    byte[] content = "the object's bytes".getBytes(UTF_8);
    serve("/api/v1/objects/" + ID, content, "0".repeat(64), content.length);
    Path saved = dir.resolve("out.bin");

    assertThat(run("--url", url(), "get", ID, "-o", saved.toString())).isEqualTo(1);
    assertThat(err.toString()).contains("incomplete or corrupt");
    assertThat(saved).doesNotExist();
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void getCutOffMidBodyFailsAndKeepsNothing() {
    byte[] content = "only part of it".getBytes(UTF_8);
    // Promises more bytes than it sends, then the connection closes: a controller abort.
    serve("/api/v1/objects/" + ID, content, Checksums.sha256Hex(content), content.length + 100);
    Path saved = dir.resolve("out.bin");

    assertThat(run("--url", url(), "get", ID, "-o", saved.toString())).isEqualTo(1);
    assertThat(saved).doesNotExist();
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void controllerErrorIsPrintedAsCodeAndMessage() {
    respond(
        "/api/v1/objects/" + ID + "/metadata",
        404,
        "{\"error\":\"object_not_found\",\"message\":\"No object with ID " + ID + "\"}");

    assertThat(run("--url", url(), "stat", ID)).isEqualTo(1);
    assertThat(err.toString())
        .isEqualTo("objstore: object_not_found: No object with ID " + ID + System.lineSeparator());
  }

  @Test
  void metaPatchesTheKeysAndRmReportsTheDelete() {
    respond("/api/v1/objects/" + ID + "/metadata", 200, "{\"custom_metadata\":{\"k\":\"v\"}}");
    assertThat(run("--url", url(), "meta", ID, "k=v")).isZero();
    assertThat(requestBody.get()).isEqualTo("{\"k\":\"v\"}");

    respond("/api/v1/objects/" + ID, 204, "");
    assertThat(run("--url", url(), "rm", ID)).isZero();
    assertThat(out.toString()).contains("Deleted " + ID);
  }

  @Test
  void unreachableControllerIsAFailureWithAClearMessage() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertThat(run("--url", "http://127.0.0.1:" + closedPort, "ls")).isEqualTo(1);
    assertThat(err.toString()).contains("cannot reach the controller");
  }

  private int run(String... args) {
    return new CommandLine(new ObjstoreCli())
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .execute(args);
  }

  private String url() {
    return "http://127.0.0.1:" + controller.getAddress().getPort();
  }

  /** Answers a path with a fixed status and JSON body, recording the request. */
  private void respond(String path, int status, String json) {
    controller.createContext(
        path,
        exchange -> {
          record(exchange);
          byte[] body = json.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
          try (OutputStream response = exchange.getResponseBody()) {
            response.write(body);
          }
        });
  }

  /** Serves a download with the given checksum header, promising {@code length} bytes. */
  private void serve(String path, byte[] content, String checksum, long length) {
    controller.createContext(
        path,
        exchange -> {
          exchange.getResponseHeaders().set("X-Checksum-SHA256", checksum);
          exchange.sendResponseHeaders(200, length);
          OutputStream response = exchange.getResponseBody();
          response.write(content);
          response.flush();
          // Closing short of the promised length drops the connection.
          exchange.close();
        });
  }

  private void record(HttpExchange exchange) throws IOException {
    requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
  }
}
