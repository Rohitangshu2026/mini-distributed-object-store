package io.github.rohitangshu2026.objstore.common;

import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates object IDs as UUID version 7 (RFC 9562): a 48-bit Unix timestamp in milliseconds,
 * followed by the version, the variant and 74 random bits.
 *
 * <p>Compared by their canonical lowercase strings, which is how the database orders them, the IDs
 * guarantee exactly this:
 *
 * <ul>
 *   <li>each ID encodes the millisecond in which it was created;
 *   <li>an ID created in a later millisecond sorts after one created in an earlier millisecond;
 *   <li>IDs created in the same millisecond have no guaranteed order, because the bits after the
 *       timestamp are random.
 * </ul>
 *
 * <p>{@link UUID#compareTo} compares the halves as signed numbers, so it does not give this order;
 * compare the strings instead. The time and the random bits come from the injected {@link Clock}
 * and {@link RandomGenerator} (rule A7). The generator is thread-safe if the random source is.
 */
public final class UuidV7 {

  private static final long VERSION_7 = 0x7000L;
  private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

  private final Clock clock;
  private final RandomGenerator random;

  /**
   * Creates a generator.
   *
   * @param clock the source of the timestamp
   * @param random the source of the random bits, for example a {@code SecureRandom}
   */
  public UuidV7(Clock clock, RandomGenerator random) {
    this.clock = clock;
    this.random = random;
  }

  /** {@return a new ID for the current millisecond} */
  public UUID next() {
    // 48 bits of milliseconds, 4 bits of version, 12 random bits.
    long high = (clock.millis() << 16) | VERSION_7 | (random.nextLong() & 0x0fffL);
    // 2 bits of variant, 62 random bits.
    long low = VARIANT_RFC_9562 | (random.nextLong() >>> 2);
    return new UUID(high, low);
  }
}
