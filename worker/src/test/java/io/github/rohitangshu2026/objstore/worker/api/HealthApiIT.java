package io.github.rohitangshu2026.objstore.worker.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tests the worker's {@code /health} over real HTTP on a random port. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthApiIT {

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void workerSettings(DynamicPropertyRegistry registry) {
    registry.add("objstore.worker-id", () -> "worker7");
    registry.add("objstore.data-dir", () -> dataDir.toString());
  }

  @LocalServerPort int port;

  @Autowired BuildProperties build;

  @Test
  void reportsIdentityVersionAndBootCount() throws IOException, InterruptedException {
    HttpResponse<String> response;
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
              .timeout(Duration.ofSeconds(5))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());
    assertThat(health.get("status").asString()).isEqualTo("UP");
    assertThat(health.get("worker_id").asString()).isEqualTo("worker7");
    assertThat(health.get("version").asString()).isEqualTo(build.getVersion());
    assertThat(health.get("boot_count").asLong()).isEqualTo(1);
    assertThat(health.get("chunk_count").asLong()).isZero();
  }
}
