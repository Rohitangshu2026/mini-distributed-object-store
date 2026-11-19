package io.github.rohitangshu2026.objstore.controller.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tests the controller's internal worker endpoints over real HTTP on a random port. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InternalApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("controller.db"));
  }

  @LocalServerPort int port;

  @Test
  void registrationThenHeartbeatShowsTheWorkerInTheClusterView() throws Exception {
    HttpResponse<String> registered =
        post(
            "/internal/workers/register",
            "{\"worker_id\":\"worker1\",\"url\":\"http://worker1:9000\",\"boot_count\":1}");
    HttpResponse<String> heartbeat =
        post(
            "/internal/workers/heartbeat",
            "{\"worker_id\":\"worker1\",\"chunk_count\":3,\"used_bytes\":3000,"
                + "\"corrupt_chunks\":[]}");

    assertThat(registered.statusCode()).isEqualTo(200);
    assertThat(json(registered).get("worker_id").asString()).isEqualTo("worker1");
    assertThat(json(registered).get("registered_at").asString()).endsWith("Z");
    assertThat(heartbeat.statusCode()).isEqualTo(204);

    JsonNode worker = json(get("/internal/workers")).get("workers").get(0);
    assertThat(worker.get("worker_id").asString()).isEqualTo("worker1");
    assertThat(worker.get("url").asString()).isEqualTo("http://worker1:9000");
    assertThat(worker.get("state").asString()).isIn("SUSPECT", "ALIVE");
    assertThat(worker.get("reported_chunk_count").asLong()).isEqualTo(3);
    assertThat(worker.get("used_bytes").asLong()).isEqualTo(3000);
    assertThat(worker.get("healthy_replicas").asLong()).isZero();
  }

  @Test
  void malformedRegistrationsAreRejected() throws Exception {
    assertError(
        post("/internal/workers/register", "{\"worker_id\":\"Worker 1\",\"url\":\"http://w:9\"}"),
        400,
        "invalid_request");
    assertError(
        post("/internal/workers/register", "{\"worker_id\":\"worker1\",\"url\":\"ftp://w:9\"}"),
        400,
        "invalid_request");
    assertError(post("/internal/workers/register", "not json"), 400, "invalid_request");
  }

  @Test
  void heartbeatFromAnUnregisteredWorkerGets404() throws Exception {
    assertError(
        post(
            "/internal/workers/heartbeat",
            "{\"worker_id\":\"worker9\",\"chunk_count\":0,\"used_bytes\":0}"),
        404,
        "worker_not_found");
  }

  @Test
  void liveInventoryIsFetchedFromTheWorkerUnchanged() throws Exception {
    String inventory = "{\"worker_id\":\"worker2\",\"chunks\":[],\"quarantined\":[]}";
    HttpServer stub =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    stub.createContext(
        "/internal/workers/worker2/chunks",
        exchange -> {
          byte[] body = inventory.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stub.start();
    try {
      register("worker2", "http://127.0.0.1:" + stub.getAddress().getPort());

      HttpResponse<String> response = get("/internal/workers/worker2/chunks");

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(json(response)).isEqualTo(JSON.readTree(inventory));
    } finally {
      stub.stop(0);
    }
  }

  @Test
  void liveInventoryOfAStoppedWorkerIs503() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    register("worker3", "http://127.0.0.1:" + closedPort);

    assertError(get("/internal/workers/worker3/chunks"), 503, "worker_unavailable");
  }

  @Test
  void liveInventoryOfAnUnknownWorkerIs404() throws Exception {
    assertError(get("/internal/workers/worker9/chunks"), 404, "worker_not_found");
  }

  private void register(String workerId, String url) throws Exception {
    HttpResponse<String> response =
        post(
            "/internal/workers/register",
            "{\"worker_id\":\"" + workerId + "\",\"url\":\"" + url + "\",\"boot_count\":1}");
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    JsonNode body = json(response);
    assertThat(body.get("error").asString()).isEqualTo(code);
    assertThat(body.get("message").asString()).isNotBlank();
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri(path)).GET());
  }

  private HttpResponse<String> post(String path, String json)
      throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(
          request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
