package io.github.rohitangshu2026.objstore.controller.object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the custom metadata rules of {@code docs/api.md} §6. */
class CustomMetadataTest {

  @Test
  void flatObjectIsStoredCompactlyWithItsTypesAndExactNumbers() {
    String stored =
        CustomMetadata.validate(
            "{ \"project\": \"cc\", \"version\": 2, \"ratio\": 0.1000000000000000055511151231257827,"
                + " \"final\": true }");

    assertThat(stored)
        .isEqualTo(
            "{\"project\":\"cc\",\"version\":2,\"ratio\":0.1000000000000000055511151231257827,"
                + "\"final\":true}");
  }

  @Test
  void keysUpTo128CharactersAndDocumentsUpTo16KbAreAccepted() {
    CustomMetadata.validate("{\"" + "k".repeat(128) + "\":1}");
    // {"k":"…"} is 8 bytes of syntax around the value.
    CustomMetadata.validate("{\"k\":\"" + "v".repeat(CustomMetadata.MAX_BYTES - 8) + "\"}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[1, 2]",
        "\"text\"",
        "not json",
        "{\"a\": {\"nested\": 1}}",
        "{\"a\": [1]}",
        "{\"a\": null}",
        "{\"\": 1}",
        "{\"a\": 1, \"a\": 2}"
      })
  void invalidDocumentsAreRejected(String json) {
    assertInvalid(json);
  }

  @Test
  void tooLongKeysAndTooLargeDocumentsAreRejected() {
    assertInvalid("{\"" + "k".repeat(129) + "\":1}");
    assertInvalid("{\"k\":\"" + "v".repeat(CustomMetadata.MAX_BYTES - 7) + "\"}");
  }

  private static void assertInvalid(String json) {
    assertThatExceptionOfType(ApiException.class)
        .isThrownBy(() -> CustomMetadata.validate(json))
        .extracting(ApiException::code)
        .isEqualTo(ErrorCode.INVALID_METADATA);
  }
}
