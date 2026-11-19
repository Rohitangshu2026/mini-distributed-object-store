package io.github.rohitangshu2026.objstore.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The storage worker application. A worker stores and verifies chunk bytes on its own volume and
 * knows nothing about objects (rule A2).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerApplication {

  /**
   * Starts the worker.
   *
   * @param args command-line arguments, passed on to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
