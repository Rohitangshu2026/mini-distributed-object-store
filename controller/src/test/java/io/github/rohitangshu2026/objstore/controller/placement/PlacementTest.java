package io.github.rohitangshu2026.objstore.controller.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/** Properties of {@link Placement}: distinct workers, exclusions respected, load spread evenly. */
class PlacementTest {

  @Property
  void neverChoosesAWorkerTwiceOrAnExcludedOne(
      @ForAll @IntRange(min = 2, max = 8) int workerCount,
      @ForAll @IntRange(min = 0, max = 10_000) int chunkIndex,
      @ForAll @Size(max = 3) Set<@IntRange(min = 0, max = 7) Integer> excludedPositions,
      @ForAll @Size(max = 8) List<@IntRange(min = 0, max = 50) Integer> loads) {
    List<String> workers = workers(workerCount);
    Set<String> exclude = new HashSet<>();
    excludedPositions.stream()
        .filter(p -> p < workerCount)
        .forEach(p -> exclude.add(workers.get(p)));
    Map<String, Long> load = new HashMap<>();
    for (int i = 0; i < Math.min(loads.size(), workerCount); i++) {
      load.put(workers.get(i), (long) loads.get(i));
    }

    List<String> chosen = Placement.choose(workers, load, chunkIndex, exclude, 2);

    assertThat(chosen).doesNotHaveDuplicates().noneMatch(exclude::contains);
    assertThat(chosen).hasSize(Math.min(2, workerCount - exclude.size()));
  }

  @Property
  void consecutiveChunksSpreadEvenlyOverEveryWorker(
      @ForAll @IntRange(min = 2, max = 8) int workerCount,
      @ForAll @IntRange(min = 1, max = 40) int chunks) {
    List<String> workers = workers(workerCount);
    Map<String, Long> load = new HashMap<>();

    for (int index = 0; index < chunks; index++) {
      for (String worker : Placement.choose(workers, load, index, Set.of(), 2)) {
        load.merge(worker, 1L, Long::sum);
      }
    }

    // Copies differ by at most one between any two workers, so every worker is used once
    // there are enough copies to go round.
    long most = workers.stream().mapToLong(w -> load.getOrDefault(w, 0L)).max().orElseThrow();
    long least = workers.stream().mapToLong(w -> load.getOrDefault(w, 0L)).min().orElseThrow();
    assertThat(most - least).isLessThanOrEqualTo(1);
  }

  private static List<String> workers(int count) {
    return IntStream.rangeClosed(1, count).mapToObj(i -> "worker" + i).toList();
  }
}
