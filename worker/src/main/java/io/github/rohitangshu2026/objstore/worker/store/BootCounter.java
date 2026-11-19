package io.github.rohitangshu2026.objstore.worker.store;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Counts this worker's starts in {@code DATA_DIR/boot_count}, so a restart shows in {@code
 * /health}. The count goes up once, at startup. Any problem with the file stops startup instead of
 * resetting the count.
 */
@Component
public class BootCounter {

  private static final String FILE = "boot_count";
  private static final String TEMP_FILE = FILE + ".tmp";

  private final long count;

  public BootCounter(WorkerProperties properties) {
    Path dataDir = properties.dataDir();
    try {
      count = increment(dataDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot update the boot count in " + dataDir, e);
    }
  }

  /** Returns the number of starts, including this one. */
  public long count() {
    return count;
  }

  private static long increment(Path dataDir) throws IOException {
    long next = read(dataDir.resolve(FILE)) + 1;
    // Write a temp file, then rename it over the old one: a crash leaves the old count or the new
    // one, never a partial file. Forcing the directory makes the rename itself durable.
    Path temp = dataDir.resolve(TEMP_FILE);
    try (FileChannel channel = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
      channel.write(ByteBuffer.wrap(Long.toString(next).getBytes(US_ASCII)));
      channel.force(true);
    }
    Files.move(temp, dataDir.resolve(FILE), ATOMIC_MOVE, REPLACE_EXISTING);
    try (FileChannel directory = FileChannel.open(dataDir, READ)) {
      directory.force(true);
    }
    return next;
  }

  private static long read(Path file) throws IOException {
    if (Files.notExists(file)) {
      return 0;
    }
    String content = Files.readString(file, US_ASCII).strip();
    long count;
    try {
      count = Long.parseLong(content);
    } catch (NumberFormatException e) {
      throw new IllegalStateException(file + " is not a number; fix or delete it by hand", e);
    }
    if (count < 0) {
      throw new IllegalStateException(file + " holds a negative count; fix or delete it by hand");
    }
    return count;
  }
}
