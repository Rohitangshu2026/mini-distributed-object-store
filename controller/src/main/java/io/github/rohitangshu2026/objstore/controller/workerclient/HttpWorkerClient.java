package io.github.rohitangshu2026.objstore.controller.workerclient;

import io.github.rohitangshu2026.objstore.common.api.Inventory;
import io.github.rohitangshu2026.objstore.common.error.ErrorBody;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls workers over HTTP/1.1 with the bounded waits of rule N9: 1 s to connect and 5 s for the
 * whole call, body included. Every failure is classified as {@code docs/architecture.md} §10.1
 * describes.
 */
@Component
public class HttpWorkerClient implements WorkerClient {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

  private final RestClient rest;

  /** Creates the client with the project's fixed timeouts. */
  public HttpWorkerClient() {
    // HTTP/1.1 because Tomcat would otherwise be asked to upgrade every connection to HTTP/2.
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
    // Spring applies this to reading the body too, so a worker that stalls mid-body is cut off.
    requests.setReadTimeout(CALL_TIMEOUT);
    this.rest = RestClient.builder().requestFactory(requests).build();
  }

  @Override
  public void putChunk(
      WorkerRef worker, String chunkId, byte[] bytes, int length, String checksum) {
    try {
      rest.put()
          .uri(worker.url() + "/internal/chunks/{chunkId}", chunkId)
          .header("X-Checksum-SHA256", checksum)
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .contentLength(length)
          .body(output -> output.write(bytes, 0, length))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new WorkerCallException(classify(e), worker.workerId(), "put_chunk", e);
    }
  }

  @Override
  public byte[] getChunk(WorkerRef worker, String chunkId) {
    try {
      byte[] bytes =
          rest.get()
              .uri(worker.url() + "/internal/chunks/{chunkId}", chunkId)
              .retrieve()
              .body(byte[].class);
      // An empty body would only come from a broken response; a stored chunk is never empty.
      if (bytes == null || bytes.length == 0) {
        throw new RestClientException("Empty chunk response");
      }
      return bytes;
    } catch (RestClientException e) {
      throw new WorkerCallException(classify(e), worker.workerId(), "get_chunk", e);
    }
  }

  @Override
  public void deleteChunk(WorkerRef worker, String chunkId) {
    try {
      rest.delete()
          .uri(worker.url() + "/internal/chunks/{chunkId}", chunkId)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new WorkerCallException(classify(e), worker.workerId(), "delete_chunk", e);
    }
  }

  @Override
  public Inventory inventory(WorkerRef worker) {
    try {
      Inventory inventory =
          rest.get()
              .uri(worker.url() + "/internal/workers/{workerId}/chunks", worker.workerId())
              .retrieve()
              .body(Inventory.class);
      if (inventory == null) {
        throw new RestClientException("Empty inventory response");
      }
      return inventory;
    } catch (RestClientException e) {
      throw new WorkerCallException(classify(e), worker.workerId(), "inventory", e);
    }
  }

  /** Classifies a failed call by what the worker answered, or by how the connection failed. */
  static Failure classify(RestClientException e) {
    if (e instanceof RestClientResponseException response) {
      return switch (response.getStatusCode().value()) {
        case 503 -> Failure.UNREACHABLE;
        case 404 -> isChunkNotFound(response) ? Failure.NOT_FOUND : Failure.REJECTED;
        case 409 -> Failure.CORRUPT;
        default ->
            response.getStatusCode().is4xxClientError()
                ? Failure.REJECTED
                : Failure.OUTCOME_UNKNOWN;
      };
    }
    // Failing to connect means the request was never sent; anything later may have taken effect.
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof ConnectException
          || cause instanceof HttpConnectTimeoutException
          || cause instanceof UnknownHostException
          || cause instanceof UnresolvedAddressException) {
        return Failure.UNREACHABLE;
      }
    }
    return Failure.OUTCOME_UNKNOWN;
  }

  /** A 404 is a missing chunk only if the worker says so; anything else is a wrong path. */
  private static boolean isChunkNotFound(RestClientResponseException response) {
    try {
      ErrorBody body = response.getResponseBodyAs(ErrorBody.class);
      return body != null && "chunk_not_found".equals(body.error());
    } catch (RestClientException e) {
      // An unreadable 404 body did not come from a worker's chunk endpoint.
      return false;
    }
  }
}
