package io.github.rohitangshu2026.objstore.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats stored timestamps for the API. The database keeps epoch milliseconds, which compare
 * correctly as numbers; the API shows them as RFC 3339 in UTC with exactly three fraction digits.
 */
public final class Timestamps {

  // A fixed pattern, not ISO_INSTANT, which drops the fraction when it is zero.
  private static final DateTimeFormatter RFC_3339_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private Timestamps() {}

  /**
   * Formats a timestamp for the API.
   *
   * @param epochMillis the time in milliseconds since the Unix epoch
   * @return the time in RFC 3339 form, for example {@code 2026-11-19T10:15:30.123Z}
   */
  public static String format(long epochMillis) {
    return RFC_3339_MILLIS.format(Instant.ofEpochMilli(epochMillis));
  }
}
