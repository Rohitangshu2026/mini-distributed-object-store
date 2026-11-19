package io.github.rohitangshu2026.objstore.worker.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rohitangshu2026.objstore.common.Checksums;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the worker's chunk API over real HTTP on a random port, including the exact status and
 * error body of each failure.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChunkApiIT {

  private static final String OBJECT_ID = "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void workerSettings(DynamicPropertyRegistry registry) {
    registry.add("objstore.worker-id", () -> "worker7");
    registry.add("objstore.data-dir", () -> dataDir.toString());
  }

  @LocalServerPort int port;

  @Test
  void putGetAndDeleteRoundTrip() throws Exception {
    String id = chunk(1);
    byte[] data = bytes(5_000, 1);
    long chunksBefore = health().get("chunk_count").asLong();

    HttpResponse<byte[]> put = put(id, Checksums.sha256Hex(data), data, "application/octet-stream");
    assertThat(put.statusCode()).isEqualTo(200);
    JsonNode stored = json(put);
    assertThat(stored.get("chunk_id").asString()).isEqualTo(id);
    assertThat(stored.get("size").asLong()).isEqualTo(data.length);
    assertThat(stored.get("checksum_sha256").asString()).isEqualTo(Checksums.sha256Hex(data));

    HttpResponse<byte[]> get = send("GET", "/internal/chunks/" + id);
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(data);
    assertThat(get.headers().firstValue("Content-Type")).hasValue("application/octet-stream");
    assertThat(get.headers().firstValue("Content-Length")).hasValue(String.valueOf(data.length));
    assertThat(get.headers().firstValue("X-Checksum-SHA256")).hasValue(Checksums.sha256Hex(data));

    assertThat(inventoryChunkIds()).contains(id);
    assertThat(health().get("chunk_count").asLong()).isEqualTo(chunksBefore + 1);

    assertThat(send("DELETE", "/internal/chunks/" + id).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", "/internal/chunks/" + id).statusCode()).isEqualTo(204);
    assertError(send("GET", "/internal/chunks/" + id), 404, "chunk_not_found");
  }

  @Test
  void putSentAsCurlDoesByDefaultIsStoredUnchanged() throws Exception {
    String id = chunk(2);
    byte[] data = bytes(5_000, 2);

    HttpResponse<byte[]> put =
        put(id, Checksums.sha256Hex(data), data, "application/x-www-form-urlencoded");

    assertThat(put.statusCode()).isEqualTo(200);
    assertThat(send("GET", "/internal/chunks/" + id).body()).isEqualTo(data);
  }

  @Test
  void badChunkIdIsRejected() throws Exception {
    assertError(send("GET", "/internal/chunks/not-a-chunk"), 400, "invalid_chunk_id");
    assertError(
        send("GET", "/internal/chunks/" + chunk(3).toUpperCase(Locale.ROOT)),
        400,
        "invalid_chunk_id");
  }

  @Test
  void missingOrMalformedChecksumIsRejected() throws Exception {
    byte[] data = bytes(10, 4);

    assertError(put(chunk(4), null, data, "application/octet-stream"), 400, "missing_checksum");
    assertError(
        put(
            chunk(4),
            Checksums.sha256Hex(data).toUpperCase(Locale.ROOT),
            data,
            "application/octet-stream"),
        400,
        "missing_checksum");
    assertThat(inventoryChunkIds()).doesNotContain(chunk(4));
  }

  @Test
  void emptyBodyIsRejected() throws Exception {
    assertError(
        put(chunk(5), Checksums.sha256Hex(new byte[0]), new byte[0], "application/octet-stream"),
        400,
        "invalid_chunk_size");
  }

  @Test
  void bodyNotMatchingItsChecksumIs400ChecksumMismatch() throws Exception {
    byte[] data = bytes(10, 6);

    assertError(
        put(chunk(6), Checksums.sha256Hex(bytes(10, 7)), data, "application/octet-stream"),
        400,
        "checksum_mismatch");
    assertThat(inventoryChunkIds()).doesNotContain(chunk(6));
  }

  @Test
  void corruptCopyIs409ChecksumMismatchAndQuarantined() throws Exception {
    String id = chunk(8);
    byte[] data = bytes(5_000, 8);
    put(id, Checksums.sha256Hex(data), data, "application/octet-stream");
    flipFirstByte(storedFile(id));

    assertError(send("GET", "/internal/chunks/" + id), 409, "checksum_mismatch");
    JsonNode inventory = json(send("GET", "/internal/workers/worker7/chunks"));
    assertThat(inventory.get("quarantined").valueStream().map(JsonNode::asString)).contains(id);
    assertError(send("GET", "/internal/chunks/" + id), 404, "chunk_not_found");
  }

  @Test
  void inventoryOfAnotherWorkerIsNotFound() throws Exception {
    assertError(send("GET", "/internal/workers/worker9/chunks"), 404, "worker_not_found");
  }

  @Test
  void unknownPathAndWrongMethodUseTheErrorFormat() throws Exception {
    assertError(send("GET", "/internal/nothing-here"), 404, "not_found");

    HttpResponse<byte[]> post = send("POST", "/internal/chunks/" + chunk(9));
    assertError(post, 405, "method_not_allowed");
    assertThat(post.headers().firstValue("Allow")).isPresent();
  }

  // Helpers

  private static String chunk(int index) {
    return OBJECT_ID + "-" + String.format(Locale.ROOT, "%06d", index);
  }

  private static byte[] bytes(int size, long seed) {
    byte[] data = new byte[size];
    new Random(seed).nextBytes(data);
    return data;
  }

  private HttpResponse<byte[]> put(String id, String checksum, byte[] body, String contentType)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        request("/internal/chunks/" + id)
            .header("Content-Type", contentType)
            .PUT(BodyPublishers.ofByteArray(body));
    if (checksum != null) {
      request.header("X-Checksum-SHA256", checksum);
    }
    return send(request.build());
  }

  private HttpResponse<byte[]> send(String method, String path)
      throws IOException, InterruptedException {
    return send(request(path).method(method, BodyPublishers.noBody()).build());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(5));
  }

  private static HttpResponse<byte[]> send(HttpRequest request)
      throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private JsonNode health() throws IOException, InterruptedException {
    return json(send("GET", "/health"));
  }

  private List<String> inventoryChunkIds() throws IOException, InterruptedException {
    return json(send("GET", "/internal/workers/worker7/chunks"))
        .get("chunks")
        .valueStream()
        .map(chunk -> chunk.get("chunk_id").asString())
        .toList();
  }

  private static JsonNode json(HttpResponse<byte[]> response) {
    return JSON.readTree(response.body());
  }

  private static void assertError(HttpResponse<byte[]> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    JsonNode body = json(response);
    assertThat(body.get("error").asString()).isEqualTo(code);
    assertThat(body.get("message").asString()).isNotBlank();
  }

  private static Path storedFile(String id) throws IOException {
    try (Stream<Path> files = Files.walk(dataDir.resolve("chunks"))) {
      return files
          .filter(file -> file.getFileName().toString().startsWith(id + "."))
          .filter(file -> !file.getFileName().toString().endsWith(".corrupt"))
          .findFirst()
          .orElseThrow();
    }
  }

  private static void flipFirstByte(Path file) throws IOException {
    byte[] data = Files.readAllBytes(file);
    data[0] ^= (byte) 0xff;
    Files.write(file, data);
  }
}
