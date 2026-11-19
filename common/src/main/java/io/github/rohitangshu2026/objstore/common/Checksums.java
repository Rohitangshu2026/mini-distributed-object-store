package io.github.rohitangshu2026.objstore.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Computes and checks SHA-256 checksums in the one form the system uses everywhere: 64 lowercase
 * hex characters, in the API, in the metadata and in worker file names.
 */
public final class Checksums {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final HexFormat HEX = HexFormat.of();

  private Checksums() {}

  /**
   * Returns a new SHA-256 digest, for hashing bytes as they stream past.
   *
   * @return a new digest; digests are not thread-safe, so use one per stream
   */
  public static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // Unreachable in practice: the Java SE specification requires every runtime to support it.
      throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
    }
  }

  /**
   * Formats a finished digest as lowercase hex.
   *
   * @param digest the bytes returned by {@link MessageDigest#digest()}
   * @return the digest as lowercase hex, 64 characters for SHA-256
   */
  public static String toHex(byte[] digest) {
    return HEX.formatHex(digest);
  }

  /**
   * Returns the SHA-256 checksum of a byte array.
   *
   * @param bytes the bytes to hash
   * @return the checksum as 64 lowercase hex characters
   */
  public static String sha256Hex(byte[] bytes) {
    return toHex(newSha256().digest(bytes));
  }

  /**
   * Returns whether a string is a SHA-256 checksum in the system's form.
   *
   * @param value the string to check, which may be {@code null}
   * @return {@code true} if {@code value} is exactly 64 lowercase hex characters
   */
  public static boolean isSha256Hex(String value) {
    return value != null && SHA256_HEX.matcher(value).matches();
  }
}
