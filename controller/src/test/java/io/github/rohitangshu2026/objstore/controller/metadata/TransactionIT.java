package io.github.rohitangshu2026.objstore.controller.metadata;

import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.NOW;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.chunk;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.committed;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.objectId;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.sqliteResultCode;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.uploading;
import static io.github.rohitangshu2026.objstore.controller.metadata.MetadataFixtures.worker;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks how transactions behave with the real SQLite settings.
 *
 * <p>Every transaction takes the write lock when it begins ({@code transaction_mode=IMMEDIATE}), so
 * a read-modify-write that starts while another is open waits for it and then reads the committed
 * value. A writer that waits longer than the 5 s busy timeout fails loudly, which is not the same
 * as a lost update: a lost update is a write that reports success but overwrote a newer value. Each
 * counter below is a JSON number in an object's custom metadata.
 */
class TransactionIT extends SqliteTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired TransactionTemplate transactions;
  @Autowired ObjectRepository objects;
  @Autowired WorkerRepository workers;

  @Test
  void exceptionInsideATransactionRollsBackEveryStatement() {
    String id = objectId(1);

    assertThatIllegalStateException()
        .isThrownBy(() ->
            transactions.executeWithoutResult(status -> {
              workers.insert(worker("worker1"));
              objects.insert(uploading(id));
              objects.insertChunk(chunk(id, 0));
              throw new IllegalStateException("fails after three inserts");
            }));

    assertThat(workers.find("worker1")).isEmpty();
    assertThat(objects.find(id)).isEmpty();
    assertThat(objects.chunksOf(id)).isEmpty();
  }

  @Test
  void interleavedReadModifyWritesBothApply() throws Exception {
    String id = counter(2);
    CountDownLatch firstHasRead = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondHasRead = new CountDownLatch(1);
    AtomicInteger secondSaw = new AtomicInteger(-1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          threads.submit(() ->
              transactions.executeWithoutResult(status -> {
                int count = count(id);
                firstHasRead.countDown();
                await(releaseFirst);
                setCount(id, count + 1);
              }));
      assertThat(firstHasRead.await(5, SECONDS)).isTrue();

      Future<?> second =
          threads.submit(() ->
              transactions.executeWithoutResult(status -> {
                int count = count(id);
                secondSaw.set(count);
                secondHasRead.countDown();
                setCount(id, count + 1);
              }));
      // The first transaction has held the write lock since its BEGIN, so the second cannot even
      // start, let alone read the value the first is about to change.
      assertThat(secondHasRead.await(500, MILLISECONDS)).isFalse();

      releaseFirst.countDown();
      first.get(10, SECONDS);
      second.get(10, SECONDS);
    } finally {
      threads.shutdownNow();
    }

    // The second began after the first committed, so it read 1 rather than a stale 0.
    assertThat(secondSaw).hasValue(1);
    assertThat(count(id)).isEqualTo(2);
  }

  /**
   * The control for {@link #interleavedReadModifyWritesBothApply}: the same interleaving on
   * connections set to {@code DEFERRED}, which takes no lock until the first write. It shows that
   * the IMMEDIATE setting is what turns a conflict into an orderly wait.
   */
  @Test
  void withDeferredTransactionsTheSameInterleavingFailsInsteadOfWaiting() throws SQLException {
    String id = counter(3);
    SQLiteConfig config = new SQLiteConfig();
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5_000);
    config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
    SQLiteDataSource deferred = new SQLiteDataSource(config);
    deferred.setUrl(databaseUrl());

    try (Connection first = deferred.getConnection();
        Connection second = deferred.getConnection()) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      int firstRead = count(first, id);
      int secondRead = count(second, id);
      setCount(first, id, firstRead + 1);
      first.commit();

      long start = System.nanoTime();
      SQLException rejected =
          catchThrowableOfType(SQLException.class, () -> setCount(second, id, secondRead + 1));
      Duration waited = Duration.ofNanos(System.nanoTime() - start);
      second.rollback();

      // Both read 0. SQLite will not let the second build on a read older than the first's
      // commit, and says so at once instead of waiting: the second update fails, nothing is lost.
      assertThat(firstRead).isZero();
      assertThat(secondRead).isZero();
      assertThat(sqliteResultCode(rejected)).isEqualTo(SQLiteErrorCode.SQLITE_BUSY.code);
      assertThat(waited).isLessThan(Duration.ofSeconds(1));
    }
    assertThat(count(id)).isEqualTo(1);
  }

  @Test
  void writerWaitingPastTheBusyTimeoutFailsLoudlyAndWritesNothing() throws Exception {
    String id = counter(4);
    CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService threads = Executors.newSingleThreadExecutor();
    try {
      Future<?> first =
          threads.submit(() ->
              transactions.executeWithoutResult(status -> {
                setCount(id, 1);
                firstHoldsTheLock.countDown();
                await(releaseFirst);
              }));
      assertThat(firstHoldsTheLock.await(5, SECONDS)).isTrue();

      long start = System.nanoTime();
      Throwable failure =
          catchThrowable(() -> transactions.executeWithoutResult(status -> setCount(id, 99)));
      Duration waited = Duration.ofNanos(System.nanoTime() - start);
      releaseFirst.countDown();
      first.get(10, SECONDS);

      // It waited out the 5 s busy timeout, then failed with SQLITE_BUSY at BEGIN, which Spring
      // reports as a transaction that could not be opened.
      assertThat(failure).isInstanceOf(CannotCreateTransactionException.class);
      assertThat(sqliteResultCode(failure)).isEqualTo(SQLiteErrorCode.SQLITE_BUSY.code);
      assertThat(waited).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(10));
    } finally {
      threads.shutdownNow();
    }
    assertThat(count(id)).isEqualTo(1);
  }

  @Test
  void manyConcurrentReadModifyWritesLoseNoUpdate() throws Exception {
    String id = counter(5);
    int writers = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(writers);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        results.add(
            threads.submit(() -> {
              await(start);
              transactions.executeWithoutResult(status -> setCount(id, count(id) + 1));
            }));
      }
      start.countDown();
      for (Future<?> result : results) {
        result.get(30, SECONDS);
      }
    } finally {
      threads.shutdownNow();
    }

    assertThat(count(id)).isEqualTo(writers);
  }

  /** Inserts an object whose custom metadata holds a counter at 0, and returns its ID. */
  private String counter(int n) {
    String id = objectId(n);
    objects.insert(committed(id, 1));
    setCount(id, 0);
    return id;
  }

  private int count(String id) {
    return parseCount(objects.find(id).orElseThrow().customMetadata());
  }

  private void setCount(String id, int count) {
    objects.updateCustomMetadata(id, "{\"count\":" + count + "}", NOW);
  }

  private static int count(Connection connection, String id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT custom_metadata FROM objects WHERE object_id = ?")) {
      select.setString(1, id);
      try (ResultSet result = select.executeQuery()) {
        result.next();
        return parseCount(result.getString(1));
      }
    }
  }

  private static void setCount(Connection connection, String id, int count) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement("UPDATE objects SET custom_metadata = ? WHERE object_id = ?")) {
      update.setString(1, "{\"count\":" + count + "}");
      update.setString(2, id);
      update.executeUpdate();
    }
  }

  private static int parseCount(String customMetadata) {
    return JSON.readTree(customMetadata).get("count").asInt();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting", e);
    }
  }
}
