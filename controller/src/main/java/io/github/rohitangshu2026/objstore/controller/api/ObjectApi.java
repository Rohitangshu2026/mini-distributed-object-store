package io.github.rohitangshu2026.objstore.controller.api;

import io.github.rohitangshu2026.objstore.common.Timestamps;
import io.github.rohitangshu2026.objstore.common.api.ObjectList;
import io.github.rohitangshu2026.objstore.common.api.ObjectMetadata;
import io.github.rohitangshu2026.objstore.common.api.ObjectSummary;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.object.CustomMetadata;
import io.github.rohitangshu2026.objstore.controller.object.DownloadService;
import io.github.rohitangshu2026.objstore.controller.object.ObjectService;
import io.github.rohitangshu2026.objstore.controller.object.UploadService;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** The client API for objects ({@code docs/api.md} §3). */
@RestController
class ObjectApi {

  /** Raw metadata larger than this cannot be valid, so it is not read any further. */
  private static final int MAX_METADATA_PART_BYTES = 64 * 1024;

  /** A lowercase canonical UUID, the only form an object ID takes ({@code docs/api.md} §1). */
  private static final Pattern OBJECT_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 1000;

  private final UploadService uploads;
  private final DownloadService downloads;
  private final ObjectService objects;

  ObjectApi(UploadService uploads, DownloadService downloads, ObjectService objects) {
    this.uploads = uploads;
    this.downloads = downloads;
    this.objects = objects;
  }

  /**
   * Downloads an object: {@code GET /api/v1/objects/{object_id}}.
   *
   * <p>The headers are sent before the first chunk is read, so every check that can still become an
   * error status runs first. A failure after that aborts the response.
   *
   * @param objectId the object's ID
   * @param response the response, written directly so no async timeout applies
   * @throws ApiException {@link ErrorCode#INVALID_OBJECT_ID}, {@link ErrorCode#OBJECT_NOT_FOUND},
   *     {@link ErrorCode#OBJECT_NOT_COMMITTED} or {@link ErrorCode#CHUNK_UNAVAILABLE}
   * @throws IOException if the client stops reading
   */
  @GetMapping("/api/v1/objects/{objectId}")
  void download(@PathVariable String objectId, HttpServletResponse response) throws IOException {
    DownloadService.Download download = downloads.prepare(requireObjectId(objectId));
    ObjectRow object = download.object();
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(object.contentType());
    response.setContentLengthLong(object.size());
    response.setHeader("X-Checksum-SHA256", object.checksumSha256());
    // Commit the headers now, so a later failure always aborts instead of becoming a status.
    response.flushBuffer();
    downloads.write(download, response.getOutputStream());
  }

  /**
   * Returns an object's metadata: {@code GET /api/v1/objects/{object_id}/metadata}.
   *
   * @param objectId the object's ID
   * @return the system and custom metadata, with chunks and copy locations
   * @throws ApiException {@link ErrorCode#INVALID_OBJECT_ID} or {@link ErrorCode#OBJECT_NOT_FOUND}
   */
  @GetMapping("/api/v1/objects/{objectId}/metadata")
  ObjectMetadata metadata(@PathVariable String objectId) {
    return objects.metadata(requireObjectId(objectId));
  }

  /**
   * Adds or updates custom metadata keys: {@code PATCH /api/v1/objects/{object_id}/metadata}.
   *
   * @param objectId the object's ID
   * @param body the keys to add or update, a flat JSON object
   * @return the object's metadata after the change
   * @throws ApiException {@link ErrorCode#INVALID_OBJECT_ID}, {@link ErrorCode#INVALID_METADATA},
   *     {@link ErrorCode#OBJECT_NOT_FOUND} or {@link ErrorCode#OBJECT_NOT_COMMITTED}
   */
  @PatchMapping(
      path = "/api/v1/objects/{objectId}/metadata",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  ObjectMetadata patchMetadata(@PathVariable String objectId, @RequestBody String body) {
    return objects.patch(requireObjectId(objectId), body);
  }

  /**
   * Deletes an object: {@code DELETE /api/v1/objects/{object_id}}. Never 503: copies on workers
   * that cannot be reached keep durable tombstones.
   *
   * @param objectId the object's ID
   * @throws ApiException {@link ErrorCode#INVALID_OBJECT_ID}, {@link ErrorCode#OBJECT_NOT_FOUND} or
   *     {@link ErrorCode#OBJECT_NOT_COMMITTED}
   */
  @DeleteMapping("/api/v1/objects/{objectId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String objectId) {
    objects.delete(requireObjectId(objectId));
  }

  /**
   * Lists committed objects: {@code GET /api/v1/objects?limit=&offset=}.
   *
   * @param limit 1 to 1000; 50 if absent
   * @param offset 0 or more; 0 if absent
   * @return one page of objects and the total
   * @throws ApiException {@link ErrorCode#INVALID_REQUEST} if a parameter is out of range or not a
   *     number
   */
  @GetMapping("/api/v1/objects")
  ObjectList list(
      @RequestParam(required = false) String limit, @RequestParam(required = false) String offset) {
    return objects.list(
        intParam("limit", limit, DEFAULT_LIMIT, 1, MAX_LIMIT),
        intParam("offset", offset, 0, 0, Integer.MAX_VALUE));
  }

  /**
   * Uploads an object: {@code POST /api/v1/objects}, {@code multipart/form-data} with exactly one
   * {@code file} part and at most one {@code metadata} part.
   *
   * <p>The request is resolved lazily, so fewer than 2 ALIVE workers are reported before the body
   * is staged. Every part is checked before any chunk is written.
   *
   * @param request the lazily parsed multipart request
   * @return 201 with the object's summary and its {@code Location}
   * @throws ApiException {@link ErrorCode#INSUFFICIENT_WORKERS}, {@link ErrorCode#MISSING_FILE},
   *     {@link ErrorCode#INVALID_METADATA} or {@link ErrorCode#INVALID_REQUEST}
   */
  @PostMapping(path = "/api/v1/objects", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<ObjectSummary> upload(MultipartHttpServletRequest request) {
    List<WorkerRef> alive = uploads.aliveWorkers();
    List<Part> files = new ArrayList<>();
    List<Part> metadata = new ArrayList<>();
    for (Part part : parts(request)) {
      switch (part.getName()) {
        case "file" -> files.add(part);
        case "metadata" -> metadata.add(part);
        default ->
            throw new ApiException(
                ErrorCode.INVALID_REQUEST,
                "Unexpected part '" + part.getName() + "'; only 'file' and 'metadata' are allowed");
      }
    }
    if (files.isEmpty()) {
      throw new ApiException(ErrorCode.MISSING_FILE, "The upload has no 'file' part");
    }
    if (files.size() > 1 || metadata.size() > 1) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "Send exactly one 'file' part and at most one 'metadata' part");
    }
    String customMetadata =
        metadata.isEmpty() ? "{}" : CustomMetadata.validate(read(metadata.get(0)));
    Part file = files.get(0);
    String name = blankToNull(file.getSubmittedFileName());
    String contentType = contentType(file, name);

    ObjectRow object;
    try (InputStream content = file.getInputStream()) {
      object = uploads.upload(alive, name, contentType, customMetadata, content);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the staged upload", e);
    }
    return ResponseEntity.created(URI.create("/api/v1/objects/" + object.objectId()))
        .body(summary(object));
  }

  private static String requireObjectId(String objectId) {
    if (!OBJECT_ID.matcher(objectId).matches()) {
      throw new ApiException(
          ErrorCode.INVALID_OBJECT_ID, "Object IDs are lowercase canonical UUIDs");
    }
    return objectId;
  }

  /** Parses a paging parameter here, so a bad value is a 400 rather than a conversion error. */
  private static int intParam(String name, String value, int absent, int min, int max) {
    if (value == null) {
      return absent;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed >= min && parsed <= max) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Falls through to the same 400 as an out-of-range number.
    }
    throw new ApiException(
        ErrorCode.INVALID_REQUEST, name + " must be an integer from " + min + " to " + max);
  }

  private static List<Part> parts(MultipartHttpServletRequest request) {
    try {
      return List.copyOf(request.getParts());
    } catch (IOException | ServletException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The multipart body cannot be read", e);
    }
  }

  private static String read(Part part) {
    try (InputStream in = part.getInputStream()) {
      byte[] bytes = in.readNBytes(MAX_METADATA_PART_BYTES + 1);
      if (bytes.length > MAX_METADATA_PART_BYTES) {
        throw new ApiException(ErrorCode.INVALID_METADATA, "The metadata part is too large");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the staged metadata part", e);
    }
  }

  /** The part's own content type, else a guess from the name, else {@code octet-stream}. */
  private static String contentType(Part file, String name) {
    String declared = blankToNull(file.getContentType());
    if (declared != null) {
      return declared;
    }
    return MediaTypeFactory.getMediaType(name == null ? "" : name)
        .orElse(MediaType.APPLICATION_OCTET_STREAM)
        .toString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static ObjectSummary summary(ObjectRow object) {
    return new ObjectSummary(
        object.objectId(),
        object.name(),
        object.size(),
        object.contentType(),
        object.checksumSha256(),
        object.chunkCount(),
        Timestamps.format(object.createdAt()),
        Timestamps.format(object.updatedAt()),
        CustomMetadata.fromStored(object.customMetadata()));
  }
}
