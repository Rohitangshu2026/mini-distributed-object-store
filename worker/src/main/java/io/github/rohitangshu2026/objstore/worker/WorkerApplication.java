package io.github.rohitangshu2026.objstore.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** A storage worker: keeps chunk bytes on its own volume and knows nothing about objects. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
