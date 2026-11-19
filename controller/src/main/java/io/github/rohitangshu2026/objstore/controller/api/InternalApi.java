package io.github.rohitangshu2026.objstore.controller.api;

import io.github.rohitangshu2026.objstore.common.Timestamps;
import io.github.rohitangshu2026.objstore.common.api.HeartbeatRequest;
import io.github.rohitangshu2026.objstore.common.api.Inventory;
import io.github.rohitangshu2026.objstore.common.api.RegisterRequest;
import io.github.rohitangshu2026.objstore.common.api.RegisterResponse;
import io.github.rohitangshu2026.objstore.common.api.WorkerList;
import io.github.rohitangshu2026.objstore.common.api.WorkerView;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.membership.Membership;
import io.github.rohitangshu2026.objstore.controller.membership.Membership.LastSeen;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.WorkerRow;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerClient;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The controller's internal API for workers and for the admin and demo views ({@code docs/api.md}
 * §4): registration, heartbeats, the cluster view and a worker's live inventory.
 */
@RestController
class InternalApi {

  private static final Pattern WORKER_ID = Pattern.compile("[a-z0-9-]{1,32}");

  private final Membership membership;
  private final WorkerRepository workers;
  private final ReplicaRepository replicas;
  private final WorkerClient workerClient;
  private final Clock clock;

  InternalApi(
      Membership membership,
      WorkerRepository workers,
      ReplicaRepository replicas,
      WorkerClient workerClient,
      Clock clock) {
    this.membership = membership;
    this.workers = workers;
    this.replicas = replicas;
    this.workerClient = workerClient;
    this.clock = clock;
  }

  /**
   * Registers a worker: {@code POST /internal/workers/register}. Idempotent; a new URL replaces the
   * old one.
   *
   * @param request the worker's ID and URL
   * @return the worker's ID and first registration time
   * @throws ApiException {@link ErrorCode#INVALID_REQUEST} if the ID or URL is malformed
   */
  @PostMapping("/internal/workers/register")
  RegisterResponse register(@RequestBody RegisterRequest request) {
    requireWorkerId(request.workerId());
    if (!isHttpUrl(request.url())) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "url must be an absolute http(s) URL");
    }
    WorkerRow worker = membership.register(request.workerId(), request.url());
    return new RegisterResponse(worker.workerId(), Timestamps.format(worker.registeredAt()));
  }

  /**
   * Records a heartbeat: {@code POST /internal/workers/heartbeat}.
   *
   * @param heartbeat the worker's ID, stats and newly quarantined chunks
   * @throws ApiException {@link ErrorCode#INVALID_REQUEST} if the ID is malformed, or {@link
   *     ErrorCode#WORKER_NOT_FOUND} if the worker must register again
   */
  @PostMapping("/internal/workers/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void heartbeat(@RequestBody HeartbeatRequest heartbeat) {
    requireWorkerId(heartbeat.workerId());
    membership.heartbeat(heartbeat);
  }

  /**
   * Returns the cluster view: {@code GET /internal/workers}.
   *
   * @return every registered worker with its state, heartbeat age and stats
   */
  @GetMapping("/internal/workers")
  WorkerList workers() {
    long now = clock.millis();
    Map<String, Long> healthy = replicas.healthyCountByWorker();
    return new WorkerList(
        workers.findAll().stream()
            .map(worker -> {
              // Heartbeats are kept in memory; the stored time is only as fresh as the
              // last state change.
              LastSeen seen =
                  membership
                      .lastSeen(worker.workerId())
                      .orElse(new LastSeen(worker.lastHeartbeatAt(), null, null, false));
              return new WorkerView(
                  worker.workerId(),
                  worker.url(),
                  worker.state().name(),
                  Timestamps.format(seen.atMillis()),
                  now - seen.atMillis(),
                  seen.chunkCount(),
                  seen.usedBytes(),
                  healthy.getOrDefault(worker.workerId(), 0L));
            })
            .toList());
  }

  /**
   * Returns the chunks a worker actually stores, fetched live from it: {@code GET
   * /internal/workers/{worker_id}/chunks}.
   *
   * @param workerId the worker's ID
   * @return the worker's inventory, unchanged
   * @throws ApiException {@link ErrorCode#WORKER_NOT_FOUND} if the worker is not registered, or
   *     {@link ErrorCode#WORKER_UNAVAILABLE} if it cannot be asked or does not answer completely
   */
  @GetMapping("/internal/workers/{workerId}/chunks")
  Inventory chunks(@PathVariable String workerId) {
    WorkerRow worker =
        workers
            .find(workerId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.WORKER_NOT_FOUND, "Worker is not registered"));
    try {
      return workerClient.inventory(new WorkerRef(worker.workerId(), worker.url()));
    } catch (WorkerCallException e) {
      throw new ApiException(
          ErrorCode.WORKER_UNAVAILABLE, "Worker " + workerId + " did not return its inventory", e);
    }
  }

  private static void requireWorkerId(String workerId) {
    if (workerId == null || !WORKER_ID.matcher(workerId).matches()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "worker_id must match ^[a-z0-9-]{1,32}$");
    }
  }

  private static boolean isHttpUrl(String url) {
    if (url == null) {
      return false;
    }
    try {
      URI uri = new URI(url);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && uri.getHost() != null;
    } catch (URISyntaxException e) {
      // A malformed URL is the client's error, reported by the caller as 400.
      return false;
    }
  }
}
