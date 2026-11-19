package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.worker;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Tests {@link WorkerRepository} against a real SQLite file. */
class WorkerRepositoryIT extends SqliteTestBase {

  @Autowired WorkerRepository workers;

  @Test
  void workersReadBackOneByOneAndSortedByID() {
    workers.insert(worker("worker2"));
    workers.insert(worker("worker1"));

    assertThat(workers.find("worker1")).contains(worker("worker1"));
    assertThat(workers.findAll()).containsExactly(worker("worker1"), worker("worker2"));
  }

  @Test
  void unknownWorkerIsNotFound() {
    assertThat(workers.find("worker9")).isEmpty();
  }
}
