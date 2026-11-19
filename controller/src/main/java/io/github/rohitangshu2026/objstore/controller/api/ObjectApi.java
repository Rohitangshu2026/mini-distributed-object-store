package io.github.rohitangshu2026.objstore.controller.api;

import io.github.rohitangshu2026.objstore.common.Timestamps;
import io.github.rohitangshu2026.objstore.common.api.ObjectSummary;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.object.CustomMetadata;
import io.github.rohitangshu2026.objstore.controller.object.UploadService;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerRef;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** The client API for objects ({@code docs/api.md} §3). */
@RestController
class ObjectApi {

  /** Raw metadata larger than this cannot be valid, so it is not read any further. */
  private static final int MAX_METADATA_PART_BYTES = 64 * 1024;

  private final UploadService uploads;

  ObjectApi(UploadService uploads) {
    this.uploads = uploads;
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
