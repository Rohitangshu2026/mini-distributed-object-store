package io.github.rohitangshu2026.objstore.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A UTC clock for tests that stands still until the test moves it, so time-dependent code can be
 * tested without sleeping. Shared with other modules' tests through the common test-jar.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  /**
   * Creates a clock that reads {@code start} until it is advanced.
   *
   * @param start the clock's first reading
   */
  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /**
   * Moves the clock forward.
   *
   * @param amount how far to move it
   */
  public void advance(Duration amount) {
    now.updateAndGet(instant -> instant.plus(amount));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  /**
   * Returns this clock, which only supports UTC.
   *
   * @throws UnsupportedOperationException if {@code zone} is not UTC
   */
  @Override
  public Clock withZone(ZoneId zone) {
    if (!ZoneOffset.UTC.equals(zone)) {
      throw new UnsupportedOperationException("MutableClock is UTC only");
    }
    return this;
  }
}
