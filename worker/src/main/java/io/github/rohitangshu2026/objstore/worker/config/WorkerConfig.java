package io.github.rohitangshu2026.objstore.worker.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/**
 * The worker's shared beans: the client it calls the controller with, and the scheduler its
 * heartbeats run on ({@code docs/architecture.md} §5).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class WorkerConfig {

  /**
   * {@return the client for the controller, with the bounded waits of rule N9: 1 s to connect and 5
   * s for the whole call}
   *
   * @param properties the worker's settings, for the controller's URL
   */
  @Bean
  public RestClient controllerClient(WorkerProperties properties) {
    // HTTP/1.1 because Tomcat would otherwise be asked to upgrade every connection to HTTP/2.
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder()
        .baseUrl(properties.controllerUrl().toString())
        .requestFactory(requests)
        .build();
  }

  /**
   * {@return the single thread that registers with the controller and sends heartbeats, kept apart
   * from scrubbing so a slow disk never delays a heartbeat}
   */
  @Bean
  public ThreadPoolTaskScheduler heartbeatScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("heartbeat-");
    // Shutdown never deregisters: a stopped worker must look the same as a failed one.
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }
}
