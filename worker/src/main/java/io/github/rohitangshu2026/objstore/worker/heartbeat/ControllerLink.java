package io.github.rohitangshu2026.objstore.worker.heartbeat;

import io.github.rohitangshu2026.objstore.common.api.HeartbeatRequest;
import io.github.rohitangshu2026.objstore.common.api.RegisterRequest;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.BootCounter;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keeps the controller informed that this worker is alive.
 *
 * <p>Each run registers with the controller until it is accepted, then sends a heartbeat with the
 * worker's stats and the chunks it has quarantined. A failed call is simply repeated on the next
 * run, one heartbeat interval later, which is the backoff. A 404 means the controller does not know
 * this worker (for example after the controller lost its database), so the next run registers
 * again. Shutdown deliberately sends nothing, so a stopped worker looks like a failed one.
 */
@Component
public class ControllerLink {

  private static final Logger log = LoggerFactory.getLogger(ControllerLink.class);

  private final RestClient controller;
  private final WorkerProperties properties;
  private final BootCounter bootCounter;
  private final ChunkStore store;

  /** Only touched by the single heartbeat thread. */
  private boolean registered;

  /** Whether the last call failed, so a long outage is logged once rather than every interval. */
  private boolean failing;

  /**
   * Creates the link.
   *
   * @param controllerClient the client for the controller, with its URL and timeouts
   * @param properties the worker's ID and advertised URL
   * @param bootCounter this worker's start count, sent with each registration
   * @param store the chunk store, for stats and quarantined chunks
   */
  public ControllerLink(
      RestClient controllerClient,
      WorkerProperties properties,
      BootCounter bootCounter,
      ChunkStore store) {
    this.controller = controllerClient;
    this.properties = properties;
    this.bootCounter = bootCounter;
    this.store = store;
  }

  /** Runs one registration or heartbeat every heartbeat interval on the heartbeat thread. */
  @Scheduled(fixedDelayString = "${objstore.heartbeat-interval}", scheduler = "heartbeatScheduler")
  void scheduledRun() {
    runOnce();
  }

  /** Registers if this worker is not registered yet, and otherwise sends one heartbeat. */
  public void runOnce() {
    try {
      if (registered) {
        heartbeat();
      } else {
        register();
      }
      if (failing) {
        log.atInfo().addKeyValue("operation", "heartbeat").log("Controller reachable again");
        failing = false;
      }
    } catch (HttpClientErrorException.NotFound e) {
      log.atInfo()
          .addKeyValue("operation", "heartbeat")
          .log("Controller does not know this worker; registering again");
      registered = false;
    } catch (RestClientException e) {
      if (!failing) {
        log.atWarn()
            .addKeyValue("operation", registered ? "heartbeat" : "register")
            .log("Cannot reach the controller; retrying every interval: {}", e.getMessage());
        failing = true;
      }
    }
  }

  /** {@return whether the controller has accepted this worker's registration} */
  public boolean isRegistered() {
    return registered;
  }

  private void register() {
    controller
        .post()
        .uri("/internal/workers/register")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RegisterRequest(
                properties.workerId(), properties.advertiseUrl(), bootCounter.count()))
        .retrieve()
        .toBodilessEntity();
    registered = true;
    log.atInfo()
        .addKeyValue("operation", "register")
        .log("Registered with the controller as {}", properties.advertiseUrl());
  }

  private void heartbeat() {
    // Taken before sending, so chunks quarantined while the call is in flight wait for the next.
    List<String> corrupt = store.corruptToReport();
    controller
        .post()
        .uri("/internal/workers/heartbeat")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new HeartbeatRequest(
                properties.workerId(), store.chunkCount(), store.usedBytes(), corrupt))
        .retrieve()
        .toBodilessEntity();
    // RestClient throws for anything but a 2xx, so a failure keeps the list for the next heartbeat.
    store.reported(corrupt);
  }
}
