package io.github.rohitangshu2026.objstore.worker.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import io.github.rohitangshu2026.objstore.worker.store.BootCounter;
import io.github.rohitangshu2026.objstore.worker.store.ChunkStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Checks how the worker registers, sends heartbeats and recovers, against a mock controller. */
class ControllerLinkTest {

  private static final String CONTROLLER = "http://controller:8080";
  private static final String REGISTER = CONTROLLER + "/internal/workers/register";
  private static final String HEARTBEAT = CONTROLLER + "/internal/workers/heartbeat";
  private static final ChunkId CHUNK = new ChunkId("019a8f2e-0000-7000-8000-000000000001-000000");

  @TempDir Path dataDir;

  private MockRestServiceServer controller;
  private ChunkStore store;
  private ControllerLink link;

  @BeforeEach
  void setUp() {
    WorkerProperties properties =
        new WorkerProperties(
            "worker1",
            dataDir,
            URI.create(CONTROLLER),
            "http://worker1:9000",
            Duration.ofSeconds(3));
    RestClient.Builder client = RestClient.builder().baseUrl(CONTROLLER);
    controller = MockRestServiceServer.bindTo(client).build();
    store = new ChunkStore(properties);
    link = new ControllerLink(client.build(), properties, new BootCounter(properties), store);
  }

  @Test
  void registrationRepeatsUntilAcceptedThenHeartbeatsFollow() {
    controller
        .expect(requestTo(REGISTER))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    controller
        .expect(requestTo(REGISTER))
        .andExpect(
            content()
                .json(
                    "{\"worker_id\":\"worker1\",\"url\":\"http://worker1:9000\",\"boot_count\":1}"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    controller
        .expect(requestTo(HEARTBEAT))
        .andExpect(
            content()
                .json(
                    "{\"worker_id\":\"worker1\",\"chunk_count\":0,\"used_bytes\":0,"
                        + "\"corrupt_chunks\":[]}"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    link.runOnce();
    assertThat(link.isRegistered()).isFalse();
    link.runOnce();
    assertThat(link.isRegistered()).isTrue();
    link.runOnce();

    controller.verify();
  }

  @Test
  void heartbeatAnswered404LeadsToRegisteringAgain() {
    controller
        .expect(requestTo(REGISTER))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    controller.expect(requestTo(HEARTBEAT)).andRespond(withStatus(HttpStatus.NOT_FOUND));
    controller
        .expect(requestTo(REGISTER))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    link.runOnce();
    link.runOnce();
    assertThat(link.isRegistered()).isFalse();
    link.runOnce();

    assertThat(link.isRegistered()).isTrue();
    controller.verify();
  }

  @Test
  void corruptChunksAreReportedUntilAHeartbeatSucceedsThenNotAgain() throws IOException {
    quarantineACorruptCopy();
    String reported =
        "{\"worker_id\":\"worker1\",\"chunk_count\":0,\"used_bytes\":0,\"corrupt_chunks\":[\""
            + CHUNK
            + "\"]}";
    controller
        .expect(requestTo(REGISTER))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    controller
        .expect(requestTo(HEARTBEAT))
        .andExpect(content().json(reported))
        .andRespond(withServerError());
    controller
        .expect(requestTo(HEARTBEAT))
        .andExpect(content().json(reported))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));
    controller
        .expect(requestTo(HEARTBEAT))
        .andExpect(content().json("{\"corrupt_chunks\":[]}"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    for (int run = 0; run < 4; run++) {
      link.runOnce();
    }

    controller.verify();
  }

  /** Stores a chunk, flips a byte on disk, and reads it so the store quarantines it. */
  private void quarantineACorruptCopy() throws IOException {
    byte[] data = "some chunk bytes".getBytes(StandardCharsets.UTF_8);
    store.put(CHUNK, Checksums.sha256Hex(data), new ByteArrayInputStream(data));
    Path file;
    try (Stream<Path> files = Files.walk(dataDir.resolve("chunks"))) {
      file =
          files
              .filter(f -> f.getFileName().toString().startsWith(CHUNK.value()))
              .findFirst()
              .orElseThrow();
    }
    byte[] stored = Files.readAllBytes(file);
    stored[0] ^= 1;
    Files.write(file, stored);
    assertThat(catchThrowable(() -> store.get(CHUNK))).isNotNull();
    assertThat(store.corruptToReport()).containsExactly(CHUNK.value());
  }
}
