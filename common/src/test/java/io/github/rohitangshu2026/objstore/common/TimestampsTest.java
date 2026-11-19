package io.github.rohitangshu2026.objstore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/** Tests that {@link Timestamps} writes RFC 3339 UTC with exactly millisecond precision. */
class TimestampsTest {

  /** The last millisecond of the year 9999, the largest four-digit year. */
  private static final long LAST_FOUR_DIGIT_YEAR_MILLIS = 253_402_300_799_999L;

  @Example
  void formatsTheEpoch() {
    assertThat(Timestamps.format(0)).isEqualTo("1970-01-01T00:00:00.000Z");
  }

  @Example
  void keepsTheMilliseconds() {
    long millis = Instant.parse("2026-11-19T10:15:30.123Z").toEpochMilli();

    assertThat(Timestamps.format(millis)).isEqualTo("2026-11-19T10:15:30.123Z");
  }

  @Example
  void keepsZeroMillisecondsInsteadOfDroppingThem() {
    long millis = Instant.parse("2026-11-19T10:15:30Z").toEpochMilli();

    assertThat(Timestamps.format(millis)).isEqualTo("2026-11-19T10:15:30.000Z");
  }

  @Property
  void roundTripsAtMillisecondPrecision(
      @ForAll @LongRange(min = 0, max = LAST_FOUR_DIGIT_YEAR_MILLIS) long millis) {
    String formatted = Timestamps.format(millis);

    assertThat(formatted).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    assertThat(Instant.parse(formatted).toEpochMilli()).isEqualTo(millis);
  }
}
