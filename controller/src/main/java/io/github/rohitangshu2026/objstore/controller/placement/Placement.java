package io.github.rohitangshu2026.objstore.controller.placement;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Chooses the workers for a chunk's copies. Workers with the fewest copy rows come first, so new
 * data flows to the emptiest workers; among equals, the starting position rotates with the chunk
 * index, so consecutive chunks spread over every worker instead of piling onto the first ones.
 */
public final class Placement {

  private Placement() {}

  /**
   * Chooses up to {@code count} distinct workers.
   *
   * @param workers the eligible (ALIVE) worker IDs, sorted
   * @param load each worker's number of copy rows; a missing worker counts as 0
   * @param chunkIndex the chunk's position in its object, which rotates the tie-break
   * @param exclude workers that already hold a row or tombstone for the chunk, or have failed it
   * @param count how many workers are wanted
   * @return the chosen worker IDs, best first; fewer than {@code count} if not enough are eligible
   */
  public static List<String> choose(
      List<String> workers,
      Map<String, Long> load,
      long chunkIndex,
      Set<String> exclude,
      int count) {
    int n = workers.size();
    return IntStream.range(0, n)
        .filter(position -> !exclude.contains(workers.get(position)))
        .boxed()
        .sorted(
            Comparator.<Integer>comparingLong(
                    position -> load.getOrDefault(workers.get(position), 0L))
                .thenComparingLong(position -> Math.floorMod(position - chunkIndex, n)))
        .limit(count)
        .map(workers::get)
        .toList();
  }
}
