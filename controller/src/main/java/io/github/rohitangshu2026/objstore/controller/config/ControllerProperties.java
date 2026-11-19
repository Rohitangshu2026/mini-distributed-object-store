package io.github.rohitangshu2026.objstore.controller.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The controller's settings, bound from the environment through {@code application.yml}.
 *
 * @param heartbeatInterval how often workers send heartbeats ({@code HEARTBEAT_INTERVAL_MS})
 * @param suspectAfterMissed how many missed heartbeats make a worker SUSPECT ({@code
 *     SUSPECT_AFTER_MISSED})
 * @param deadAfterMissed how many missed heartbeats make a worker DEAD ({@code DEAD_AFTER_MISSED})
 * @param repairConcurrency how many chunks are repaired at once ({@code REPAIR_CONCURRENCY})
 * @param gcGrace how long an orphan file must keep appearing before it is deleted ({@code
 *     GC_GRACE_SECS})
 * @param startupGrace how long after startup reconciliation may create tombstones even if some
 *     worker has not sent its inventory yet ({@code STARTUP_GRACE_SECS})
 * @param gcDryRun whether orphan cleanup only logs what it would delete ({@code GC_DRY_RUN})
 */
@ConfigurationProperties("objstore")
public record ControllerProperties(
    Duration heartbeatInterval,
    int suspectAfterMissed,
    int deadAfterMissed,
    int repairConcurrency,
    Duration gcGrace,
    Duration startupGrace,
    boolean gcDryRun) {

  /**
   * Checks the settings as Spring binds them.
   *
   * @throws IllegalArgumentException if a setting is out of range, which stops startup
   */
  public ControllerProperties {
    if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
      throw new IllegalArgumentException("HEARTBEAT_INTERVAL_MS must be positive");
    }
    if (suspectAfterMissed < 1) {
      throw new IllegalArgumentException("SUSPECT_AFTER_MISSED must be at least 1");
    }
    if (deadAfterMissed <= suspectAfterMissed) {
      throw new IllegalArgumentException(
          "DEAD_AFTER_MISSED must be greater than SUSPECT_AFTER_MISSED");
    }
    if (repairConcurrency < 1) {
      throw new IllegalArgumentException("REPAIR_CONCURRENCY must be at least 1");
    }
    if (gcGrace == null
        || gcGrace.isNegative()
        || startupGrace == null
        || startupGrace.isNegative()) {
      throw new IllegalArgumentException(
          "GC_GRACE_SECS and STARTUP_GRACE_SECS must not be negative");
    }
  }

  /** {@return the heartbeat age at which a worker becomes SUSPECT} */
  public Duration suspectAfter() {
    return heartbeatInterval.multipliedBy(suspectAfterMissed);
  }

  /** {@return the heartbeat age at which a worker becomes DEAD} */
  public Duration deadAfter() {
    return heartbeatInterval.multipliedBy(deadAfterMissed);
  }
}
