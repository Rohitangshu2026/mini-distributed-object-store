package io.github.rohitangshu2026.objstore.controller.api;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerState;
import io.github.rohitangshu2026.objstore.controller.workerclient.Failure;
import io.github.rohitangshu2026.objstore.controller.workerclient.FakeWorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
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

/** Tests the object endpoints over real HTTP, with in-memory workers behind them. */
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

  @Autowired WorkerClient workerClient;

  @BeforeEach
  void fourAliveWorkers() {
    for (String table : List.of("tombstones", "chunk_replicas", "chunks", "objects", "workers")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
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

  @Test
  void downloadSendsTheBytesWithTheStoredHeaders() throws Exception {
    byte[] content = new byte[2 * CHUNK_SIZE + 5];
    new Random(5).nextBytes(content);
    String id = uploadedId(part("file", "sample.pdf", null, content));

    HttpResponse<byte[]> response =
        sendForBytes(HttpRequest.newBuilder(URI.create(uri() + "/" + id)).GET());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
    assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(content.length);
    assertThat(response.headers().firstValue("X-Checksum-SHA256"))
        .hasValue(Checksums.sha256Hex(content));
    assertThat(response.body()).isEqualTo(content);
  }

  @Test
  void downloadThatFailsMidStreamEndsShortOfContentLength() throws Exception {
    byte[] content = new byte[4 * CHUNK_SIZE];
    String id = uploadedId(part("file", "four.bin", null, content));
    FakeWorkerClient fake = (FakeWorkerClient) workerClient;
    jdbc.sql("SELECT chunk_id, worker_id FROM chunk_replicas WHERE chunk_id = ?")
        .param(id + "-000002")
        .query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
        .list()
        .forEach(copy -> fake.failReads(copy.get(1), copy.get(0), Failure.OUTCOME_UNKNOWN));

    long received = 0;
    boolean cutOff = false;
    // The client stays open while the body is read: closing it waits for the exchange to end.
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      HttpResponse<InputStream> response =
          client.send(
              HttpRequest.newBuilder(URI.create(uri() + "/" + id))
                  .timeout(Duration.ofSeconds(30))
                  .build(),
              BodyHandlers.ofInputStream());
      assertThat(response.statusCode()).isEqualTo(200);
      try (InputStream body = response.body()) {
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = body.read(buffer)) != -1; ) {
          received += n;
        }
      } catch (IOException e) {
        cutOff = true;
      }
    }

    // The two good chunks arrive, then the connection is dropped instead of completing.
    assertThat(cutOff || received < content.length).isTrue();
    assertThat(received).isLessThanOrEqualTo(2L * CHUNK_SIZE);
  }

  @Test
  void metadataShowsChunksAndWhereTheirCopiesAre() throws Exception {
    String id = uploadedId(part("file", "m.bin", null, new byte[CHUNK_SIZE + 1]));

    HttpResponse<String> response = get("/" + id + "/metadata");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("status").asString()).isEqualTo("COMMITTED");
    assertThat(body.get("chunk_count").asInt()).isEqualTo(2);
    JsonNode chunks = body.get("chunks");
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(1).get("index").asInt()).isEqualTo(1);
    assertThat(chunks.get(1).get("size").asInt()).isEqualTo(1);
    for (JsonNode chunk : chunks) {
      assertThat(chunk.get("replicas")).hasSize(2);
      assertThat(chunk.get("replicas").get(0).get("status").asString()).isEqualTo("HEALTHY");
      assertThat(chunk.get("replicas").get(0).get("worker_id").asString())
          .isNotEqualTo(chunk.get("replicas").get(1).get("worker_id").asString());
    }
  }

  @Test
  void badOrUnknownIdsAreRejected() throws Exception {
    assertError(get("/not-a-uuid"), 400, "invalid_object_id");
    assertError(get("/019A8F2E-0000-7000-8000-000000000000/metadata"), 400, "invalid_object_id");
    assertError(get("/019a8f2e-0000-7000-8000-000000000000"), 404, "object_not_found");
    assertError(get("/019a8f2e-0000-7000-8000-000000000000/metadata"), 404, "object_not_found");
  }

  @Test
  void listPagesThroughCommittedObjectsInCreationOrder() throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(uploadedId(part("file", "f" + i, null, new byte[] {(byte) i})));
    }

    JsonNode first = JSON.readTree(get("?limit=2").body());
    JsonNode second = JSON.readTree(get("?limit=2&offset=2").body());
    JsonNode past = JSON.readTree(get("?offset=10").body());

    assertThat(first.get("objects")).hasSize(2);
    assertThat(first.get("objects").get(0).get("object_id").asString()).isEqualTo(ids.get(0));
    assertThat(first.get("total").asLong()).isEqualTo(3);
    assertThat(first.get("limit").asInt()).isEqualTo(2);
    assertThat(second.get("objects")).hasSize(1);
    assertThat(second.get("objects").get(0).get("object_id").asString()).isEqualTo(ids.get(2));
    assertThat(past.get("objects")).isEmpty();
    assertThat(past.get("total").asLong()).isEqualTo(3);
    assertThat(past.get("limit").asInt()).isEqualTo(50);
  }

  @Test
  void listRejectsOutOfRangeOrNonNumericPaging() throws Exception {
    for (String query : List.of("?limit=0", "?limit=1001", "?offset=-1", "?limit=ten")) {
      assertError(get(query), 400, "invalid_request");
    }
    assertThat(get("?limit=1000").statusCode()).isEqualTo(200);
  }

  private String uploadedId(byte[]... parts) throws Exception {
    HttpResponse<String> response = post(parts);
    assertThat(response.statusCode()).isEqualTo(201);
    return JSON.readTree(response.body()).get("object_id").asString();
  }

  private HttpResponse<String> get(String suffix) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(URI.create(uri() + suffix)).GET());
  }

  private static HttpResponse<byte[]> sendForBytes(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return send(request, BodyHandlers.ofByteArray());
  }

  private static <T> HttpResponse<T> send(
      HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(request.timeout(Duration.ofSeconds(30)).build(), handler);
    }
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
    return send(request, BodyHandlers.ofString());
  }

  private URI uri() {
    return URI.create("http://localhost:" + port + "/api/v1/objects");
  }
}
