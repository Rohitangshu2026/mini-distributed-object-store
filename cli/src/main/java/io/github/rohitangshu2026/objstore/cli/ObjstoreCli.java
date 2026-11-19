package io.github.rohitangshu2026.objstore.cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.rohitangshu2026.objstore.common.Checksums;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

/**
 * The {@code objstore} command-line client. It is plain Java with no Spring, so it starts quickly
 * (decision 0002). Every command prints the controller's JSON, or a one-line error on stderr, and
 * exits with 0 on success, 1 on a failure and 2 on a usage error.
 */
@Command(
    name = "objstore",
    mixinStandardHelpOptions = true,
    versionProvider = ObjstoreCli.BuildVersion.class,
    description = "Client for the mini distributed object store.")
public class ObjstoreCli implements Runnable {

  private static final int OK = 0;
  private static final int FAILED = 1;

  /** This command's model, injected by picocli and used for output and usage errors. */
  @Spec CommandSpec spec;

  /** The controller's URL. */
  @Option(
      names = "--url",
      defaultValue = "${env:CONTROLLER_URL:-http://localhost:8080}",
      description = "Controller URL (env CONTROLLER_URL; default: ${DEFAULT-VALUE}).")
  String url;

  /**
   * Runs the client and exits with its status.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    System.exit(new CommandLine(new ObjstoreCli()).execute(args));
  }

  /** Runs when no command is given: picocli reports the usage error and exits with code 2. */
  @Override
  public void run() {
    throw new ParameterException(spec.commandLine(), "Missing command");
  }

  /**
   * Uploads a file. The multipart body is streamed from disk, never held in memory.
   *
   * @param file the file to upload
   * @param name the object's name; the file's name if absent
   * @param contentType the object's content type; guessed by the controller from the name if absent
   * @param meta custom metadata as {@code key=value} pairs, stored as strings
   * @return the exit code
   */
  @Command(name = "put", description = "Upload a file and print the new object's summary.")
  int put(
      @Parameters(paramLabel = "FILE") Path file,
      @Option(names = "--name", description = "Object name (default: the file name).") String name,
      @Option(
              names = "--content-type",
              description = "Content type (default: guessed from the name).")
          String contentType,
      @Option(
              names = "--meta",
              paramLabel = "KEY=VALUE",
              description = "Custom metadata; repeatable.")
          Map<String, String> meta) {
    return call(http -> {
      String boundary = "objstore-" + HexFormat.of().formatHex(randomBytes(16));
      String filename = (name != null ? name : file.getFileName().toString()).replace("\"", "%22");
      StringBuilder head = new StringBuilder();
      if (meta != null && !meta.isEmpty()) {
        // Metadata first, so the controller can reject it before reading a large file.
        head.append("--")
            .append(boundary)
            .append("\r\n")
            .append("Content-Disposition: form-data; name=\"metadata\"\r\n")
            .append("Content-Type: application/json\r\n\r\n")
            .append(ControllerHttp.jsonObject(new LinkedHashMap<>(meta)))
            .append("\r\n");
      }
      head.append("--")
          .append(boundary)
          .append("\r\n")
          .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
          .append(filename)
          .append("\"\r\n");
      if (contentType != null) {
        head.append("Content-Type: ").append(contentType).append("\r\n");
      }
      head.append("\r\n");
      HttpRequest request =
          http.request("/api/v1/objects", ControllerHttp.TRANSFER_TIMEOUT)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(
                  BodyPublishers.concat(
                      BodyPublishers.ofString(head.toString(), UTF_8),
                      BodyPublishers.ofFile(file),
                      BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", UTF_8)))
              .build();
      return printJson(http.send(request, BodyHandlers.ofByteArray()), 201);
    });
  }

  /**
   * Downloads an object, then checks that every byte arrived and that their SHA-256 matches the
   * controller's {@code X-Checksum-SHA256}. A file that fails either check is deleted.
   *
   * @param objectId the object's ID
   * @param output the file to write; stdout if absent
   * @return the exit code
   */
  @Command(name = "get", description = "Download an object and verify its checksum.")
  int get(
      @Parameters(paramLabel = "OBJECT_ID") String objectId,
      @Option(
              names = {"-o", "--output"},
              description = "Output file (default: stdout).")
          Path output) {
    return call(http -> {
      HttpResponse<InputStream> response =
          http.send(
              http.request("/api/v1/objects/" + objectId, ControllerHttp.TRANSFER_TIMEOUT)
                  .GET()
                  .build(),
              BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        try (InputStream body = response.body()) {
          return fail(ControllerHttp.describeError(response.statusCode(), body.readAllBytes()));
        }
      }
      long expectedLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      String expectedChecksum = response.headers().firstValue("X-Checksum-SHA256").orElse("");
      // Written next to the target and moved into place only once verified.
      Path partial =
          output == null
              ? null
              : Files.createTempFile(output.toAbsolutePath().getParent(), ".objstore-", ".part");
      MessageDigest digest = Checksums.newSha256();
      long received;
      try (InputStream body = response.body()) {
        received = partial == null ? copyToStdout(body, digest) : copyToFile(body, partial, digest);
      } catch (IOException e) {
        deleteIfExists(partial);
        return fail(
            "download was cut off; the controller could not finish it (" + e.getMessage() + ")");
      }
      String actual = Checksums.toHex(digest.digest());
      if (received != expectedLength || !actual.equals(expectedChecksum)) {
        deleteIfExists(partial);
        return fail(
            "download incomplete or corrupt: got "
                + received
                + " of "
                + expectedLength
                + " bytes, sha256 "
                + actual);
      }
      if (partial != null) {
        Files.move(
            partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        err()
            .printf(
                Locale.ROOT,
                "Saved %d bytes to %s (sha256 %s verified)%n",
                received,
                output,
                actual);
      }
      return OK;
    });
  }

  /**
   * Lists committed objects.
   *
   * @param limit the page size
   * @param offset how many objects to skip
   * @return the exit code
   */
  @Command(name = "ls", description = "List objects, oldest first.")
  int ls(
      @Option(names = "--limit", defaultValue = "50", description = "Page size, 1-1000.") int limit,
      @Option(names = "--offset", defaultValue = "0", description = "Objects to skip.")
          int offset) {
    return call(http ->
        printJson(
            http.send(
                http.request(
                        "/api/v1/objects?limit=" + limit + "&offset=" + offset,
                        ControllerHttp.REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                BodyHandlers.ofByteArray()),
            200));
  }

  /**
   * Shows an object's metadata, with its chunks and where their copies are.
   *
   * @param objectId the object's ID
   * @return the exit code
   */
  @Command(name = "stat", description = "Show an object's metadata, chunks and copies.")
  int stat(@Parameters(paramLabel = "OBJECT_ID") String objectId) {
    return call(http ->
        printJson(
            http.send(
                http.request(
                        "/api/v1/objects/" + objectId + "/metadata", ControllerHttp.REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                BodyHandlers.ofByteArray()),
            200));
  }

  /**
   * Adds or updates custom metadata keys.
   *
   * @param objectId the object's ID
   * @param meta the keys to set, as {@code key=value} pairs stored as strings
   * @return the exit code
   */
  @Command(name = "meta", description = "Add or update custom metadata keys.")
  int meta(
      @Parameters(index = "0", paramLabel = "OBJECT_ID") String objectId,
      @Parameters(index = "1..*", paramLabel = "KEY=VALUE") Map<String, String> meta) {
    return call(http ->
        printJson(
            http.send(
                http.request(
                        "/api/v1/objects/" + objectId + "/metadata", ControllerHttp.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .method(
                        "PATCH",
                        BodyPublishers.ofString(
                            ControllerHttp.jsonObject(new LinkedHashMap<>(meta))))
                    .build(),
                BodyHandlers.ofByteArray()),
            200));
  }

  /**
   * Deletes an object.
   *
   * @param objectId the object's ID
   * @return the exit code
   */
  @Command(name = "rm", description = "Delete an object.")
  int rm(@Parameters(paramLabel = "OBJECT_ID") String objectId) {
    return call(http -> {
      HttpResponse<byte[]> response =
          http.send(
              http.request("/api/v1/objects/" + objectId, ControllerHttp.REQUEST_TIMEOUT)
                  .DELETE()
                  .build(),
              BodyHandlers.ofByteArray());
      if (response.statusCode() != 204) {
        return fail(ControllerHttp.describeError(response.statusCode(), response.body()));
      }
      out().printf(Locale.ROOT, "Deleted %s%n", objectId);
      return OK;
    });
  }

  /**
   * Shows the workers' states and how well the chunks are replicated.
   *
   * @return the exit code
   */
  @Command(name = "status", description = "Show worker states and replication health.")
  int status() {
    return call(http -> {
      HttpResponse<byte[]> workers = getJson(http, "/internal/workers");
      HttpResponse<byte[]> replication = getJson(http, "/internal/replication");
      if (workers.statusCode() != 200) {
        return fail(ControllerHttp.describeError(workers.statusCode(), workers.body()));
      }
      if (replication.statusCode() != 200) {
        return fail(ControllerHttp.describeError(replication.statusCode(), replication.body()));
      }
      PrintWriter out = out();
      out.printf(
          Locale.ROOT,
          "%-10s %-8s %10s %8s %8s%n",
          "WORKER",
          "STATE",
          "HEARTBEAT",
          "CHUNKS",
          "HEALTHY");
      for (JsonNode worker : ControllerHttp.json(workers.body()).get("workers")) {
        out.printf(
            Locale.ROOT,
            "%-10s %-8s %9.1fs %8s %8d%n",
            worker.get("worker_id").asString(),
            worker.get("state").asString(),
            worker.get("last_heartbeat_age_ms").asLong() / 1000.0,
            worker.get("reported_chunk_count").isNull()
                ? "-"
                : worker.get("reported_chunk_count").asString(),
            worker.get("healthy_replicas").asLong());
      }
      JsonNode report = ControllerHttp.json(replication.body());
      out.printf(
          Locale.ROOT,
          "%nChunks: %d total, %d healthy, %d under-replicated, %d unavailable;"
              + " %d tombstones pending%n",
          report.get("total_chunks").asLong(),
          report.get("healthy").asLong(),
          report.get("under_replicated").asLong(),
          report.get("unavailable").asLong(),
          report.get("pending_tombstones").asLong());
      out.println(fullyReplicated(report) ? "Fully replicated." : "NOT fully replicated.");
      return OK;
    });
  }

  /**
   * Downloads every object and checks its length and SHA-256, then checks that the cluster is fully
   * replicated.
   *
   * @param allowUnderReplicated check only the content, as while a worker is down
   * @return 0 if every check passed, otherwise 1
   */
  @Command(
      name = "verify",
      description = "Download and check every object, then require full replication.")
  int verify(
      @Option(
              names = "--allow-under-replicated",
              description = "Check only content, not replication (while a worker is down).")
          boolean allowUnderReplicated) {
    return call(http -> {
      int objects = 0;
      int failures = 0;
      long bytes = 0;
      for (int offset = 0; ; offset += 1000) {
        HttpResponse<byte[]> page = getJson(http, "/api/v1/objects?limit=1000&offset=" + offset);
        if (page.statusCode() != 200) {
          return fail(ControllerHttp.describeError(page.statusCode(), page.body()));
        }
        JsonNode entries = ControllerHttp.json(page.body()).get("objects");
        for (JsonNode entry : entries) {
          String id = entry.get("object_id").asString();
          String problem = checkObject(http, id);
          objects++;
          bytes += entry.get("size").asLong();
          if (problem != null) {
            failures++;
            err().println("objstore: FAILED " + id + ": " + problem);
          }
        }
        if (entries.size() < 1000) {
          break;
        }
      }
      out()
          .printf(
              Locale.ROOT, "Content: %d objects, %d bytes, %d failed%n", objects, bytes, failures);

      HttpResponse<byte[]> replication = getJson(http, "/internal/replication");
      if (replication.statusCode() != 200) {
        return fail(ControllerHttp.describeError(replication.statusCode(), replication.body()));
      }
      JsonNode report = ControllerHttp.json(replication.body());
      out()
          .printf(
              Locale.ROOT,
              "Replication: %d healthy, %d under-replicated, %d unavailable%n",
              report.get("healthy").asLong(),
              report.get("under_replicated").asLong(),
              report.get("unavailable").asLong());
      if (failures > 0) {
        return fail(failures + " objects failed verification");
      }
      if (!allowUnderReplicated && !fullyReplicated(report)) {
        return fail("the cluster is not fully replicated");
      }
      out().println("Verified.");
      return OK;
    });
  }

  /**
   * Downloads one object without keeping it and checks it.
   *
   * @return {@code null} if it is intact, otherwise what is wrong
   */
  private static String checkObject(ControllerHttp http, String objectId)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        http.send(
            http.request("/api/v1/objects/" + objectId, ControllerHttp.TRANSFER_TIMEOUT)
                .GET()
                .build(),
            BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        return ControllerHttp.describeError(response.statusCode(), body.readAllBytes());
      }
      MessageDigest digest = Checksums.newSha256();
      long received;
      try {
        received = body.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
      } catch (IOException e) {
        return "download was cut off";
      }
      long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      String checksum = response.headers().firstValue("X-Checksum-SHA256").orElse("");
      if (received != expected || !Checksums.toHex(digest.digest()).equals(checksum)) {
        return "got " + received + " of " + expected + " bytes, or a checksum mismatch";
      }
      return null;
    }
  }

  private static boolean fullyReplicated(JsonNode report) {
    return report.get("under_replicated").asLong() == 0 && report.get("unavailable").asLong() == 0;
  }

  private static HttpResponse<byte[]> getJson(ControllerHttp http, String path)
      throws IOException, InterruptedException {
    return http.send(
        http.request(path, ControllerHttp.REQUEST_TIMEOUT).GET().build(),
        BodyHandlers.ofByteArray());
  }

  /** One command's work, given a connected client. */
  private interface Call {
    int run(ControllerHttp http) throws IOException, InterruptedException;
  }

  /** Runs a command, turning a failed connection into a one-line error and exit code 1. */
  private int call(Call call) {
    try (ControllerHttp http = new ControllerHttp(url)) {
      return call.run(http);
    } catch (IOException e) {
      return fail("cannot reach the controller at " + url + " (" + e + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail("interrupted");
    }
  }

  private int printJson(HttpResponse<byte[]> response, int expectedStatus) {
    if (response.statusCode() != expectedStatus) {
      return fail(ControllerHttp.describeError(response.statusCode(), response.body()));
    }
    out().println(ControllerHttp.pretty(new String(response.body(), UTF_8)));
    return OK;
  }

  private int fail(String message) {
    err().println("objstore: " + message);
    return FAILED;
  }

  private PrintWriter out() {
    PrintWriter out = spec.commandLine().getOut();
    out.flush();
    return out;
  }

  private PrintWriter err() {
    return spec.commandLine().getErr();
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static void deleteIfExists(Path file) throws IOException {
    if (file != null) {
      Files.deleteIfExists(file);
    }
  }

  private static long copyToFile(InputStream body, Path file, MessageDigest digest)
      throws IOException {
    try (OutputStream sink = new DigestOutputStream(Files.newOutputStream(file), digest)) {
      return body.transferTo(sink);
    }
  }

  /** Flushes stdout but never closes it, so later output still reaches the terminal. */
  private static long copyToStdout(InputStream body, MessageDigest digest) throws IOException {
    DigestOutputStream sink = new DigestOutputStream(System.out, digest);
    long copied = body.transferTo(sink);
    sink.flush();
    return copied;
  }

  /** Reads the version that the Maven build records in {@code META-INF/build-info.properties}. */
  static final class BuildVersion implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
      Properties build = new Properties();
      try (InputStream in =
          ObjstoreCli.class.getResourceAsStream("/META-INF/build-info.properties")) {
        if (in == null) {
          return new String[] {"objstore (version unknown)"};
        }
        build.load(in);
      }
      return new String[] {"objstore " + build.getProperty("build.version")};
    }
  }
}
