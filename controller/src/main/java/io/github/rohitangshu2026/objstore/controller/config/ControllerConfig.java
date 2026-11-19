package io.github.rohitangshu2026.objstore.controller.config;

import io.github.rohitangshu2026.objstore.common.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The controller's shared beans: its settings, its clock and the schedulers its background jobs run
 * on ({@code docs/architecture.md} §5).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ControllerProperties.class)
@EnableScheduling
public class ControllerConfig {

  /** {@return the clock every timestamp and heartbeat age comes from (rule A7)} */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** {@return the generator of object IDs, with time from the clock and bits from a CSPRNG} */
  @Bean
  public UuidV7 objectIds(Clock clock) {
    return new UuidV7(clock, new SecureRandom());
  }

  /**
   * {@return the pool that writes the second copy of each chunk while the request thread writes the
   * first}
   *
   * <p>8 threads serve 8 concurrent uploads; the queue is larger than the number of request threads
   * that can wait on it, so a rejection would be a bug and fails the upload cleanly.
   */
  @Bean
  public ThreadPoolTaskExecutor replicaWriteExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(256);
    executor.setThreadNamePrefix("replica-write-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  /**
   * {@return the pool that repairs chunks in parallel: {@code REPAIR_CONCURRENCY} threads}
   *
   * <p>One repair run submits at most 100 chunks and waits for them, so the queue of 100 never
   * overflows.
   *
   * @param properties the repair concurrency
   */
  @Bean
  public ThreadPoolTaskExecutor repairExecutor(ControllerProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.repairConcurrency());
    executor.setMaxPoolSize(properties.repairConcurrency());
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("repair-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  /** {@return the single thread that runs repair sweeps, so two sweeps never overlap} */
  @Bean
  public ThreadPoolTaskScheduler repairScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("repair-sweep-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }

  /**
   * {@return the single thread for recovery and, in later phases, tombstones and reconciliation}
   */
  @Bean
  public ThreadPoolTaskScheduler maintenanceScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("maintenance-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }

  /**
   * {@return the single thread that runs the membership check, the only code that changes worker
   * states}
   */
  @Bean
  public ThreadPoolTaskScheduler membershipScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("membership-");
    // Let a check that is writing a transition finish, for up to 5 s.
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }
}
