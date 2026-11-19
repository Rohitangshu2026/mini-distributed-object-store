package io.github.rohitangshu2026.objstore.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The controller application. It owns the client API, the metadata, placement, membership, repair
 * and cleanup, and is the only component that knows what an object is (rule A2).
 */
@SpringBootApplication
public class ControllerApplication {

  /**
   * Starts the controller.
   *
   * @param args command-line arguments, passed on to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(ControllerApplication.class, args);
  }
}
