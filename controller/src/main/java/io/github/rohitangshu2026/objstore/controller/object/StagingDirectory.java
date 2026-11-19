package io.github.rohitangshu2026.objstore.controller.object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prepares the directory Tomcat stages uploads in (decision 0004). Before the web server starts it
 * deletes whatever a crash left there, then makes sure the directory exists, because Tomcat refuses
 * an upload whose staging directory is missing.
 */
@Component
public class StagingDirectory implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(StagingDirectory.class);

  private final Path directory;

  /**
   * Creates the preparer.
   *
   * @param directory the staging directory, {@code spring.servlet.multipart.location}
   */
  public StagingDirectory(@Value("${spring.servlet.multipart.location}") Path directory) {
    this.directory = directory;
  }

  /**
   * Wipes and creates the directory before any request is served.
   *
   * @throws UncheckedIOException if the directory cannot be prepared, which stops startup
   */
  @Override
  public void afterSingletonsInstantiated() {
    try {
      Files.createDirectories(directory);
      long deleted = 0;
      try (Stream<Path> files = Files.list(directory)) {
        for (Path file : files.toList()) {
          Files.deleteIfExists(file);
          deleted++;
        }
      }
      if (deleted > 0) {
        log.atInfo()
            .addKeyValue("operation", "startup")
            .log("Deleted {} staged uploads left by an earlier run", deleted);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot prepare the upload staging directory " + directory, e);
    }
  }
}
