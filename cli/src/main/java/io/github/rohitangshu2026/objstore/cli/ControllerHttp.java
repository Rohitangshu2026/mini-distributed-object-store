package io.github.rohitangshu2026.objstore.cli;

import io.github.rohitangshu2026.objstore.common.error.ErrorBody;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CLI's HTTP calls to the controller, with bounded waits: 1 s to connect, 5 s for a small
 * request, and 10 minutes for a transfer, because a large upload or download legitimately takes
 * longer than 5 s.
 */
final class ControllerHttp implements AutoCloseable {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final HttpClient client;

  /**
   * Creates the client.
   *
   * @param baseUrl the controller's URL, for example {@code http://localhost:8080}
   */
  ControllerHttp(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    // HTTP/1.1 because Tomcat would otherwise be asked to upgrade the connection to HTTP/2.
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
  }

  /** {@return a request builder for a path on the controller} */
  HttpRequest.Builder request(String path, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
  }

  /**
   * Sends a request.
   *
   * @param request the request
   * @param body how to read the response body
   * @return the response
   * @throws IOException if the controller could not be reached or the connection failed
   * @throws InterruptedException if interrupted while waiting
   */
  <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body)
      throws IOException, InterruptedException {
    return client.send(request, body);
  }

  /**
   * Describes a failed response for the user: the controller's error code and message when the body
   * is the API's error body, otherwise just the status.
   *
   * @param status the HTTP status
   * @param body the response body
   * @return a one-line description
   */
  static String describeError(int status, byte[] body) {
    try {
      ErrorBody error = JSON.readValue(body, ErrorBody.class);
      if (error != null && error.error() != null) {
        return error.error() + ": " + error.message();
      }
    } catch (JacksonException e) {
      // Not the API's error body, for example a proxy's page; the status says enough.
    }
    return "HTTP "
        + status
        + (body.length == 0 ? "" : ": " + new String(body, StandardCharsets.UTF_8).strip());
  }

  /** {@return a JSON body pretty-printed for the terminal} */
  static String pretty(String json) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(json));
  }

  /** {@return the JSON text of a flat object of string values} */
  static String jsonObject(java.util.Map<String, String> values) {
    return JSON.writeValueAsString(values);
  }

  @Override
  public void close() {
    client.close();
  }
}
