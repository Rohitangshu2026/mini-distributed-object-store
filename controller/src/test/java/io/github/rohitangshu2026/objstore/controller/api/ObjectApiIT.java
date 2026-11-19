package io.github.rohitangshu2026.objstore.controller.api;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tests {@code POST /api/v1/objects} over real HTTP, with in-memory workers behind it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ObjectApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BOUNDARY = "objstore-test-boundary";

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void paths(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("controller.db"));
    registry.add("spring.servlet.multipart.location", () -> dataDir.resolve("staging").toString());
  }

  /** Puts in-memory workers in place of the HTTP client. */
  @TestConfiguration
  static class Workers {
    @Bean
    @Primary
    WorkerClient fakeWorkerClient() {
      return new FakeWorkerClient();
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired WorkerRepository workers;

  @BeforeEach
  void fourAliveWorkers() {
    jdbc.sql("DELETE FROM chunk_replicas").update();
    jdbc.sql("DELETE FROM workers").update();
    for (int i = 1; i <= 4; i++) {
      workers.insert(
          new WorkerRow("worker" + i, "http://worker" + i + ":9000", WorkerState.ALIVE, 0, 0));
    }
  }

  @Test
  void uploadReturns201WithLocationAndSummary() throws Exception {
    byte[] content = new byte[CHUNK_SIZE + 1];
    Arrays.fill(content, (byte) 7);

    HttpResponse<String> response =
        post(
            part("metadata", null, null, "{\"project\":\"cc\",\"version\":2}".getBytes(UTF_8)),
            part("file", "report.pdf", null, content));

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = JSON.readTree(response.body());
    String id = body.get("object_id").asString();
    assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/objects/" + id);
    assertThat(body.get("name").asString()).isEqualTo("report.pdf");
    assertThat(body.get("content_type").asString()).isEqualTo("application/pdf");
    assertThat(body.get("size").asLong()).isEqualTo(content.length);
    assertThat(body.get("chunk_count").asInt()).isEqualTo(2);
    assertThat(body.get("checksum_sha256").asString()).isEqualTo(Checksums.sha256Hex(content));
    assertThat(body.get("created_at").asString()).endsWith("Z");
    assertThat(body.get("custom_metadata"))
        .isEqualTo(JSON.readTree("{\"project\":\"cc\",\"version\":2}"));
  }

  @Test
  void declaredContentTypeWinsAndAFileWithoutANameIsNamedByItsId() throws Exception {
    HttpResponse<String> response = post(part("file", null, "image/png", new byte[] {1, 2}));

    JsonNode body = JSON.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(body.get("content_type").asString()).isEqualTo("image/png");
    assertThat(body.get("name").asString()).isEqualTo(body.get("object_id").asString());
  }

  @Test
  void malformedUploadsAreRejectedAndStoreNothing() throws Exception {
    byte[] one = {1};
    assertError(post(part("metadata", null, null, "{}".getBytes(UTF_8))), 400, "missing_file");
    assertError(
        post(part("file", "a", null, one), part("file", "b", null, one)), 400, "invalid_request");
    assertError(
        post(part("file", "a", null, one), part("metdata", null, null, one)),
        400,
        "invalid_request");
    assertError(
        post(
            part("file", "a", null, one),
            part("metadata", null, null, "{\"a\":{}}".getBytes(UTF_8))),
        400,
        "invalid_metadata");
    assertError(
        send(
            HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))),
        400,
        "invalid_request");

    assertThat(
            jdbc.sql("SELECT count(*) FROM objects WHERE status <> 'COMMITTED'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void fewerThanTwoAliveWorkersIs503() throws Exception {
    jdbc.sql("UPDATE workers SET state = 'DEAD' WHERE worker_id <> 'worker1'").update();

    assertError(post(part("file", "a", null, new byte[] {1})), 503, "insufficient_workers");
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("error").asString()).isEqualTo(code);
    assertThat(body.get("message").asString()).isNotBlank();
  }

  /** One multipart part, already framed. */
  private static byte[] part(String name, String filename, String contentType, byte[] content) {
    StringBuilder head = new StringBuilder("--" + BOUNDARY + "\r\n");
    head.append("Content-Disposition: form-data; name=\"").append(name).append('"');
    if (filename != null) {
      head.append("; filename=\"").append(filename).append('"');
    }
    head.append("\r\n");
    if (contentType != null) {
      head.append("Content-Type: ").append(contentType).append("\r\n");
    }
    head.append("\r\n");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(head.toString().getBytes(UTF_8));
    out.writeBytes(content);
    out.writeBytes("\r\n".getBytes(UTF_8));
    return out.toByteArray();
  }

  private HttpResponse<String> post(byte[]... parts) throws IOException, InterruptedException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      body.writeBytes(part);
    }
    body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(UTF_8));
    return send(
        HttpRequest.newBuilder(uri())
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(
          request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI uri() {
    return URI.create("http://localhost:" + port + "/api/v1/objects");
  }
}
