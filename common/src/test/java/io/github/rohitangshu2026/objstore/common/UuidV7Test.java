package io.github.rohitangshu2026.objstore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Tests exactly the guarantees {@link UuidV7} documents: version and variant, the encoded
 * millisecond, and ordering across milliseconds, but not within one.
 */
class UuidV7Test {

  private static final long MAX_48_BIT_MILLIS = (1L << 48) - 1;

  @Property
  void isAVersion7RfcUuidInLowercaseCanonicalForm(
      @ForAll @LongRange(min = 0, max = MAX_48_BIT_MILLIS) long millis, @ForAll long seed) {
    UUID id = generatorAt(millis, seed).next();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).as("variant bits 10").isEqualTo(2);
    assertThat(id.toString())
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Property
  void encodesTheMillisecondItWasCreatedIn(
      @ForAll @LongRange(min = 0, max = MAX_48_BIT_MILLIS) long millis, @ForAll long seed) {
    UUID id = generatorAt(millis, seed).next();

    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
  }

  @Property
  void idFromALaterMillisecondSortsAfter(
      @ForAll @LongRange(min = 0, max = MAX_48_BIT_MILLIS - 1_000) long millis,
      @ForAll @LongRange(min = 1, max = 1_000) long laterBy,
      @ForAll long seed) {
    MutableClock clock = new MutableClock(Instant.ofEpochMilli(millis));
    UuidV7 ids = new UuidV7(clock, new Random(seed));

    String earlier = ids.next().toString();
    clock.advance(Duration.ofMillis(laterBy));
    String later = ids.next().toString();

    // Compared as strings, as the database orders them; UUID.compareTo is signed and would not.
    assertThat(later).isGreaterThan(earlier);
  }

  @Example
  void idsFromOneMillisecondAreUniqueButInNoParticularOrder() {
    UuidV7 ids = generatorAt(1_763_502_400_000L, 42);

    List<String> generated = Stream.generate(ids::next).limit(100_000).map(UUID::toString).toList();

    assertThat(new HashSet<>(generated)).hasSize(100_000);
    // Within one millisecond the random bits decide the order, not the order of creation.
    assertThat(generated).isNotEqualTo(generated.stream().sorted().toList());
  }

  private static UuidV7 generatorAt(long millis, long seed) {
    return new UuidV7(new MutableClock(Instant.ofEpochMilli(millis)), new Random(seed));
  }
}
