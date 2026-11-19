package io.github.rohitangshu2026.objstore.common;

import java.util.regex.Pattern;

/**
 * The ID of one chunk of an object: {@code {object_id}-{chunk_index}}, with the index zero-padded
 * to at least 6 digits, for example {@code 019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f-000003}.
 *
 * <p>Constructing a {@code ChunkId} validates the format, so code that holds one can safely use it
 * in a file name: the format allows only lowercase hex digits, decimal digits and hyphens, which
 * rules out path traversal.
 *
 * @param value the ID in its canonical string form
 */
public record ChunkId(String value) {

  /** The format of {@code docs/api.md} §1: a lowercase canonical UUID, a hyphen, 6 to 10 digits. */
  private static final Pattern FORMAT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{6,10}");

  /**
   * Creates a chunk ID from its string form.
   *
   * @throws IllegalArgumentException if {@code value} is {@code null} or not in the chunk ID format
   */
  public ChunkId {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Not a valid chunk ID");
    }
  }

  /**
   * Returns whether a string is in the chunk ID format.
   *
   * @param value the string to check, which may be {@code null}
   * @return {@code true} if {@code value} is a valid chunk ID
   */
  public static boolean isValid(String value) {
    return value != null && FORMAT.matcher(value).matches();
  }

  /** Returns the ID in its canonical string form, as used in URLs and file names. */
  @Override
  public String toString() {
    return value;
  }
}
