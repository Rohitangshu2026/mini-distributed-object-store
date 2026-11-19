package io.github.rohitangshu2026.objstore.common;

/** How objects are split into fixed-size chunks. */
public final class Chunking {

  /**
   * Bytes per chunk: the brief's "1 MB" read as the SI megabyte (decision 0001). Only an object's
   * last chunk may be smaller.
   */
  public static final int CHUNK_SIZE = 1_000_000;

  private Chunking() {}

  /** Returns how many chunks hold an object of {@code size} bytes; a 0-byte object has none. */
  public static long chunkCount(long size) {
    if (size < 0) {
      throw new IllegalArgumentException("Object size must not be negative: " + size);
    }
    // ceilDiv cannot overflow, unlike (size + CHUNK_SIZE - 1) / CHUNK_SIZE.
    return Math.ceilDiv(size, CHUNK_SIZE);
  }
}
