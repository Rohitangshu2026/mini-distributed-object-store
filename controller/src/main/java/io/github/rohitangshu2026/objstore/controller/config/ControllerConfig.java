package io.github.rohitangshu2026.objstore.controller.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
