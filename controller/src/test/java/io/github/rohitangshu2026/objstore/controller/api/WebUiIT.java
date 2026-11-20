package io.github.rohitangshu2026.objstore.controller.api;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Checks that the controller serves the demo page at {@code /}, next to the JSON API. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebUiIT {

  @TempDir static Path dataDir;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("controller.db"));
  }

  @LocalServerPort int port;

  @Test
  void rootServesTheDemoPage() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
    assertThat(response.body()).contains("Object store demo console", "app.js", "demos.js");
  }

  @Test
  void pageScriptsAndStylesAreServedAndAlwaysRevalidated()
      throws IOException, InterruptedException {
    for (String asset : new String[] {"/app.js", "/demos.js", "/app.css"}) {
      HttpResponse<String> response = get(asset);

      assertThat(response.statusCode()).as(asset).isEqualTo(200);
      assertThat(response.headers().firstValue("Cache-Control")).as(asset).hasValue("no-cache");
    }
    assertThat(get("/demos.js").body())
        .contains("/internal/replication", "scripts/demo-6-integrity.sh");
  }

  @Test
  void unknownPathsStillGetTheApiErrorBody() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/no-such-page");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("\"error\":\"not_found\"");
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(5))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
