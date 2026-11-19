package io.github.rohitangshu2026.objstore.common;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.Locale;
import net.jqwik.api.Example;

/** Tests {@link Checksums} against known SHA-256 values and the checksum format. */
class ChecksumsTest {

  @Example
  void matchesKnownSha256Vectors() {
    assertThat(Checksums.sha256Hex(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(Checksums.sha256Hex("abc".getBytes(US_ASCII)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Example
  void streamingDigestMatchesOneShotHash() {
    MessageDigest digest = Checksums.newSha256();
    digest.update("a".getBytes(US_ASCII));
    digest.update("bc".getBytes(US_ASCII));

    assertThat(Checksums.toHex(digest.digest()))
        .isEqualTo(Checksums.sha256Hex("abc".getBytes(US_ASCII)));
  }

  @Example
  void onlySixtyFourLowercaseHexCharactersAreAChecksum() {
    String valid = Checksums.sha256Hex(new byte[0]);

    assertThat(Checksums.isSha256Hex(valid)).isTrue();
    assertThat(Checksums.isSha256Hex(valid.toUpperCase(Locale.ROOT))).isFalse();
    assertThat(Checksums.isSha256Hex(valid.substring(1))).isFalse();
    assertThat(Checksums.isSha256Hex(valid + "0")).isFalse();
    assertThat(Checksums.isSha256Hex(valid.replace('e', 'g'))).isFalse();
    assertThat(Checksums.isSha256Hex(null)).isFalse();
  }
}
