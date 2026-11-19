package io.github.rohitangshu2026.objstore.common;

/**
 * Splits objects into fixed-size chunks.
 *
 * <p>An object is stored as {@link #chunkCount(long)} chunks of {@link #CHUNK_SIZE} bytes, except
 * that its last chunk may be smaller.
 */
public final class Chunking {

  /**
   * The size in bytes of every chunk except, possibly, an object's last.
   *
   * <p>The brief's "1 MB" is read as the SI megabyte, 1,000,000 bytes (decision 0001). Switching to
   * 1 MiB means changing only this constant.
   */
  public static final int CHUNK_SIZE = 1_000_000;

  private Chunking() {}

  /**
   * Returns the number of chunks needed to hold an object.
   *
   * @param size the object's size in bytes
   * @return the number of chunks, which is 0 for an empty object
   * @throws IllegalArgumentException if {@code size} is negative
   */
  public static long chunkCount(long size) {
    if (size < 0) {
      throw new IllegalArgumentException("Object size must not be negative: " + size);
    }
    // Math.ceilDiv cannot overflow, unlike (size + CHUNK_SIZE - 1) / CHUNK_SIZE.
    return Math.ceilDiv(size, CHUNK_SIZE);
  }
}
