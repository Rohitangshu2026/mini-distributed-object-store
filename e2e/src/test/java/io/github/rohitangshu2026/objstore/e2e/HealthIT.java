package io.github.rohitangshu2026.objstore.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Runs against a cluster started with {@code make up}, through the controller's published port. */
class HealthIT {

  private static final URI CONTROLLER = URI.create("http://localhost:8080");

  @Test
  void controllerReportsUp() throws IOException, InterruptedException {
    HttpResponse<String> response;
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(CONTROLLER.resolve("/health"))
              .timeout(Duration.ofSeconds(5))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"", "\"service\":\"controller\"");
  }
}
