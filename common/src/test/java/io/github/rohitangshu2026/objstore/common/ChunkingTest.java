package io.github.rohitangshu2026.objstore.common;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static io.github.rohitangshu2026.objstore.common.Chunking.chunkCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigInteger;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class ChunkingTest {

  @Example
  void emptyObjectHasNoChunks() {
    assertThat(chunkCount(0)).isZero();
  }

  @Example
  void oneByteNeedsOneChunk() {
    assertThat(chunkCount(1)).isEqualTo(1);
  }

  @Example
  void exactlyOneChunkSizeFillsOneChunk() {
    assertThat(chunkCount(CHUNK_SIZE)).isEqualTo(1);
  }

  @Example
  void oneByteOverAChunkNeedsASecondChunk() {
    assertThat(chunkCount(CHUNK_SIZE + 1L)).isEqualTo(2);
  }

  @Example
  void largestSizeDoesNotOverflow() {
    assertThat(chunkCount(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE / CHUNK_SIZE + 1);
  }

  @Example
  void negativeSizeIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> chunkCount(-1));
  }

  /** The count is the fewest chunks that hold every byte: enough chunks, and none to spare. */
  @Property
  void chunksCoverTheObjectWithNoneToSpare(
      @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long size) {
    // BigInteger, because chunks * CHUNK_SIZE overflows a long for the largest sizes.
    BigInteger bytes = BigInteger.valueOf(size);
    BigInteger capacity =
        BigInteger.valueOf(chunkCount(size)).multiply(BigInteger.valueOf(CHUNK_SIZE));

    assertThat(capacity).isGreaterThanOrEqualTo(bytes);
    assertThat(capacity.subtract(BigInteger.valueOf(CHUNK_SIZE))).isLessThan(bytes);
  }
}
