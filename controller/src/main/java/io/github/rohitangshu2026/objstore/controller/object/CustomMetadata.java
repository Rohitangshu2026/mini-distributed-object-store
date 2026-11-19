package io.github.rohitangshu2026.objstore.controller.object;

import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks and converts custom metadata, the flat JSON object of {@code docs/api.md} §6. The same
 * rules apply to an upload's {@code metadata} part and to the result of a PATCH.
 */
public final class CustomMetadata {

  /** The largest compact serialization allowed, in bytes of UTF-8. */
  static final int MAX_BYTES = 16_384;

  /** The longest key allowed, in Unicode code points. */
  static final int MAX_KEY_LENGTH = 128;

  // Duplicate keys are rejected, and decimals keep their exact value in and out.
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .build();

  private CustomMetadata() {}

  /**
   * Parses and checks a client's metadata document.
   *
   * @param json the document
   * @return the metadata in the compact form it is stored in
   * @throws ApiException {@link ErrorCode#INVALID_METADATA} if the document breaks a rule
   */
  public static String validate(String json) {
    return toStored(parse(json));
  }

  /**
   * Parses a client's metadata document without checking its keys and values, so a PATCH can merge
   * it first and check the merged result.
   *
   * @param json the document
   * @return the parsed object
   * @throws ApiException {@link ErrorCode#INVALID_METADATA} if it is not a JSON object, or repeats
   *     a key
   */
  public static ObjectNode parse(String json) {
    JsonNode parsed;
    try {
      parsed = JSON.readTree(json);
    } catch (JacksonException e) {
      // Jackson's message may quote the input; the client gets a fixed one instead.
      throw new ApiException(
          ErrorCode.INVALID_METADATA,
          "Metadata must be a valid JSON object without duplicate keys",
          e);
    }
    if (parsed == null || !parsed.isObject()) {
      throw new ApiException(ErrorCode.INVALID_METADATA, "Metadata must be a JSON object");
    }
    return (ObjectNode) parsed;
  }

  /**
   * Checks every key and value of a metadata object and returns its stored form.
   *
   * @param metadata the metadata
   * @return the compact JSON text to store
   * @throws ApiException {@link ErrorCode#INVALID_METADATA} if the metadata breaks a rule
   */
  public static String toStored(ObjectNode metadata) {
    for (Map.Entry<String, JsonNode> entry : metadata.properties()) {
      String key = entry.getKey();
      int length = key.codePointCount(0, key.length());
      if (length < 1 || length > MAX_KEY_LENGTH) {
        throw new ApiException(
            ErrorCode.INVALID_METADATA,
            "Metadata keys must be 1 to " + MAX_KEY_LENGTH + " characters long");
      }
      JsonNode value = entry.getValue();
      if (!(value.isString() || value.isNumber() || value.isBoolean())) {
        throw new ApiException(
            ErrorCode.INVALID_METADATA,
            "Metadata value of key '" + key + "' must be a string, number or boolean");
      }
    }
    String stored = JSON.writeValueAsString(metadata);
    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new ApiException(
          ErrorCode.INVALID_METADATA, "Metadata must be at most " + MAX_BYTES + " bytes as JSON");
    }
    return stored;
  }

  /**
   * Reads stored metadata back for a response.
   *
   * @param stored the stored JSON text
   * @return the metadata object
   */
  public static ObjectNode fromStored(String stored) {
    return (ObjectNode) JSON.readTree(stored);
  }
}
