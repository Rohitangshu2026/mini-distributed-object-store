package io.github.rohitangshu2026.objstore.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/** Tests the chunk ID format of {@link ChunkId}, with generated valid IDs and known bad ones. */
class ChunkIdTest {

  private static final String OBJECT_ID = "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f";

  @Property
  void wellFormedIdsAreAccepted(
      @ForAll("objectIds") String objectId,
      @ForAll @LongRange(min = 0, max = 9_999_999_999L) long index) {
    String value = objectId + "-" + String.format(Locale.ROOT, "%06d", index);

    assertThat(new ChunkId(value).value()).isEqualTo(value);
  }

  @Example
  void indexesOfSixAndTenDigitsAreAccepted() {
    assertThat(new ChunkId(OBJECT_ID + "-000000")).hasToString(OBJECT_ID + "-000000");
    assertThat(new ChunkId(OBJECT_ID + "-9999999999")).hasToString(OBJECT_ID + "-9999999999");
  }

  @Property
  void malformedIdsAreRejected(@ForAll("malformedIds") String value) {
    assertThatIllegalArgumentException().isThrownBy(() -> new ChunkId(value));
  }

  @Example
  void nullIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ChunkId(null));
  }

  @Provide
  Arbitrary<String> objectIds() {
    return Arbitraries.strings()
        .withChars("0123456789abcdef")
        .ofLength(32)
        .map(hex ->
            String.join(
                "-",
                hex.substring(0, 8),
                hex.substring(8, 12),
                hex.substring(12, 16),
                hex.substring(16, 20),
                hex.substring(20)));
  }

  @Provide
  Arbitrary<String> malformedIds() {
    return Arbitraries.of(
        "",
        OBJECT_ID,
        OBJECT_ID.toUpperCase(Locale.ROOT) + "-000001",
        OBJECT_ID + "-00001",
        OBJECT_ID + "-00000000001",
        OBJECT_ID + "-000001\n",
        OBJECT_ID.replace("-", "") + "-000001",
        "../../etc/passwd",
        OBJECT_ID + "-000001/../x");
  }
}
