package io.github.rohitangshu2026.objstore.controller.workerclient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.rohitangshu2026.objstore.common.api.Inventory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks how {@link HttpWorkerClient} classifies every kind of failure, against a JDK {@code
 * HttpServer} stub on a random port. The stall cases prove the 5 s limit covers the whole call,
 * body included.
 */
class HttpWorkerClientIT {

  private static final String INVENTORY =
      """
      {"worker_id":"worker1","chunks":[{"chunk_id":"019a8f2e-0000-7000-8000-000000000001-000000",\
      "size":10,"checksum_sha256":"%s"}],"quarantined":[]}\
      """
          .formatted("a".repeat(64));

  private final HttpWorkerClient client = new HttpWorkerClient();
  private final CountDownLatch releaseStalls = new CountDownLatch(1);
  private HttpServer server;

  @AfterEach
  void stopServer() {
    releaseStalls.countDown();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void returnsTheInventoryAWorkerSends() {
    WorkerRef worker = serve(exchange -> respond(exchange, 200, INVENTORY));

    Inventory inventory = client.inventory(worker);

    assertThat(inventory.workerId()).isEqualTo("worker1");
    assertThat(inventory.chunks()).hasSize(1);
    assertThat(inventory.quarantined()).isEmpty();
  }

  @Test
  void connectionRefusedIsUnreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertThat(failureOf(new WorkerRef("worker1", "http://127.0.0.1:" + closedPort)))
        .isEqualTo(Failure.UNREACHABLE);
  }

  @Test
  void serviceUnavailableIsUnreachable() {
    WorkerRef worker = serve(exchange -> respond(exchange, 503, error("storage_unavailable")));

    assertThat(failureOf(worker)).isEqualTo(Failure.UNREACHABLE);
  }

  @Test
  void chunkNotFoundIsNotFoundButAnyOther404IsRejected() {
    assertThat(failureOf(serve(exchange -> respond(exchange, 404, error("chunk_not_found")))))
        .isEqualTo(Failure.NOT_FOUND);
    stopServer();
    assertThat(failureOf(serve(exchange -> respond(exchange, 404, error("worker_not_found")))))
        .isEqualTo(Failure.REJECTED);
  }

  @Test
  void checksumMismatchIsCorrupt() {
    WorkerRef worker = serve(exchange -> respond(exchange, 409, error("checksum_mismatch")));

    assertThat(failureOf(worker)).isEqualTo(Failure.CORRUPT);
  }

  @Test
  void serverErrorIsAnUnknownOutcome() {
    WorkerRef worker = serve(exchange -> respond(exchange, 500, error("internal_error")));

    assertThat(failureOf(worker)).isEqualTo(Failure.OUTCOME_UNKNOWN);
  }

  @Test
  void noResponseWithinFiveSecondsIsAnUnknownOutcome() {
    WorkerRef worker =
        serve(exchange -> {
          awaitRelease();
          respond(exchange, 200, INVENTORY);
        });

    assertFailsWithinTheCallLimit(worker);
  }

  @Test
  void bodyThatStallsAfterTheHeadersIsCutOffWithinFiveSeconds() {
    WorkerRef worker =
        serve(exchange -> {
          byte[] body = INVENTORY.getBytes(UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          OutputStream out = exchange.getResponseBody();
          out.write(body, 0, body.length / 2);
          out.flush();
          awaitRelease();
          out.write(body, body.length / 2, body.length - body.length / 2);
          out.close();
        });

    assertFailsWithinTheCallLimit(worker);
  }

  private void assertFailsWithinTheCallLimit(WorkerRef worker) {
    long start = System.nanoTime();
    Failure failure = failureOf(worker);
    Duration waited = Duration.ofNanos(System.nanoTime() - start);

    assertThat(failure).isEqualTo(Failure.OUTCOME_UNKNOWN);
    assertThat(waited)
        .isGreaterThanOrEqualTo(HttpWorkerClient.CALL_TIMEOUT.minusMillis(500))
        .isLessThan(HttpWorkerClient.CALL_TIMEOUT.plusSeconds(2));
  }

  private Failure failureOf(WorkerRef worker) {
    WorkerCallException e =
        catchThrowableOfType(WorkerCallException.class, () -> client.inventory(worker));
    assertThat(e).as("the call should have failed").isNotNull();
    assertThat(e.workerId()).isEqualTo(worker.workerId());
    assertThat(e.operation()).isEqualTo("inventory");
    return e.failure();
  }

  /** Starts a stub worker that answers every request with {@code handler}. */
  private WorkerRef serve(HttpHandler handler) {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start the stub worker", e);
    }
    server.createContext("/", handler);
    server.start();
    return new WorkerRef("worker1", "http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void awaitRelease() {
    try {
      // Longer than the client's limit; released early when the test ends.
      assertThat(releaseStalls.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static String error(String code) {
    return "{\"error\":\"" + code + "\",\"message\":\"stub\"}";
  }
}
