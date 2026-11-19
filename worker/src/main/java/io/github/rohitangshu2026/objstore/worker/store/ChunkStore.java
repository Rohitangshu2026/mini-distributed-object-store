package io.github.rohitangshu2026.objstore.worker.store;

import static io.github.rohitangshu2026.objstore.common.Chunking.CHUNK_SIZE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.rohitangshu2026.objstore.common.Checksums;
import io.github.rohitangshu2026.objstore.common.ChunkId;
import io.github.rohitangshu2026.objstore.common.api.ChunkInfo;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.worker.config.WorkerProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores the worker's chunk files under {@code DATA_DIR/chunks} and keeps an in-memory index of
 * them ({@code docs/architecture.md} §12, decision 0005).
 *
 * <p>Each chunk is one file, {@code <aa>/<bb>/<chunk_id>.<sha256>}, whose name records the checksum
 * its bytes must match. The store guarantees that:
 *
 * <ul>
 *   <li>a write is verified and forced to disk before one atomic rename publishes it, so no reader
 *       ever sees a partial or unverified copy, and a failed write leaves the existing copy alone;
 *   <li>every read re-hashes the file, because the name is a claim and never proof (rule A6);
 *   <li>a copy that fails verification is quarantined as {@code .corrupt}, never deleted on the
 *       spot, so the evidence survives for repair.
 * </ul>
 */
@Component
public class ChunkStore {

  private static final Logger log = LoggerFactory.getLogger(ChunkStore.class);

  private static final int LOCK_STRIPES = 64;
  private static final String TEMP_SUFFIX = ".tmp";
  private static final String CORRUPT_SUFFIX = ".corrupt";

  /** The {@code DATA_DIR/chunks} directory. */
  private final Path root;

  /** The servable copy of each chunk, rebuilt from the file names at boot. */
  private final Map<ChunkId, StoredFile> index = new ConcurrentHashMap<>();

  /**
   * Lock stripes. Writes, deletes and verify-then-quarantine of one chunk take its stripe's lock,
   * so none of them can interleave with another for the same chunk, while operations on different
   * chunks rarely share a stripe.
   */
  private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

  /**
   * A servable copy of a chunk.
   *
   * @param path the chunk file
   * @param size the file's size in bytes
   * @param checksum the checksum recorded in the file name, which the bytes must match
   */
  private record StoredFile(Path path, long size, String checksum) {}

  /**
   * The bytes of a copy that have just matched their recorded checksum.
   *
   * <p>A class rather than a record, because a record's {@code equals} would compare the array by
   * identity.
   */
  public static final class VerifiedChunk {

    private final byte[] bytes;
    private final String checksum;

    VerifiedChunk(byte[] bytes, String checksum) {
      this.bytes = bytes;
      this.checksum = checksum;
    }

    /** {@return the chunk's bytes} The array is not copied, so callers must not modify it. */
    public byte[] bytes() {
      return bytes;
    }

    /** {@return the checksum the bytes have just matched, as 64 lowercase hex characters} */
    public String checksum() {
      return checksum;
    }
  }

  /**
   * Opens the store: creates {@code DATA_DIR/chunks} if needed, deletes writes that never finished,
   * and rebuilds the index from the file names.
   *
   * @param properties the worker's settings, for the data directory
   * @throws UncheckedIOException if the store directory cannot be created or listed, which stops
   *     the worker from starting
   */
  public ChunkStore(WorkerProperties properties) {
    for (int i = 0; i < LOCK_STRIPES; i++) {
      locks[i] = new ReentrantLock();
    }
    root = properties.dataDir().resolve("chunks");
    try {
      createDirectory(root);
      loadIndex();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot open the chunk store in " + root, e);
    }
  }

  /**
   * Stores a chunk, replacing any existing copy of it.
   *
   * <p>The bytes go to a temp file in the chunk's directory and are hashed as they arrive. Only
   * when the size and checksum are right and the file is forced to disk does one atomic rename
   * publish it. No reader can see a partial or unverified copy, a failed write leaves any existing
   * copy untouched, and repeating the same PUT is safe.
   *
   * @param id the chunk's ID
   * @param checksum the SHA-256 the body must match, as 64 lowercase hex characters
   * @param body the chunk's bytes, read until it ends or exceeds {@code CHUNK_SIZE} bytes
   * @return the stored chunk's ID, size and checksum
   * @throws ApiException {@link ErrorCode#INVALID_CHUNK_SIZE} if the body is empty or too large,
   *     {@link ErrorCode#BODY_CHECKSUM_MISMATCH} if it does not match {@code checksum}, {@link
   *     ErrorCode#INVALID_REQUEST} if it cannot be read, or {@link ErrorCode#STORAGE_UNAVAILABLE}
   *     if a disk operation fails
   */
  public ChunkInfo put(ChunkId id, String checksum, InputStream body) {
    Path dir = directoryFor(id);
    Path temp = null;
    try {
      createDirectory(dir);
      temp = Files.createTempFile(dir, id + ".", TEMP_SUFFIX);
      long size = writeVerified(id, body, temp, checksum);
      publish(id, temp, new StoredFile(dir.resolve(id + "." + checksum), size, checksum));
      return new ChunkInfo(id.value(), size, checksum);
    } catch (IOException e) {
      throw storageUnavailable(e);
    } finally {
      // After a successful publish the temp file has been renamed away, so this deletes nothing.
      if (temp != null) {
        deleteTempFile(temp);
      }
    }
  }

  /**
   * Returns a chunk's bytes after re-hashing them against the checksum in the file name.
   *
   * <p>A copy that fails verification is quarantined: it is renamed to {@code .corrupt} and dropped
   * from the index, so the next read of the chunk finds no copy.
   *
   * @param id the chunk's ID
   * @return the verified bytes and the checksum they matched
   * @throws ApiException {@link ErrorCode#CHUNK_NOT_FOUND} if no servable copy exists, {@link
   *     ErrorCode#STORED_CHECKSUM_MISMATCH} if the copy failed verification and was quarantined, or
   *     {@link ErrorCode#STORAGE_UNAVAILABLE} if the file cannot be read or quarantined
   */
  public VerifiedChunk get(ChunkId id) {
    ReentrantLock lock = lockFor(id);
    lock.lock();
    try {
      StoredFile file = index.get(id);
      if (file == null) {
        throw new ApiException(ErrorCode.CHUNK_NOT_FOUND, "No copy of this chunk is stored here");
      }
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file.path())) {
        // One byte over the limit is enough to fail the hash of a file that has grown.
        bytes = in.readNBytes(CHUNK_SIZE + 1);
      } catch (NoSuchFileException e) {
        index.remove(id);
        log.atWarn()
            .addKeyValue("operation", "get")
            .addKeyValue("chunk_id", id.value())
            .log("Indexed chunk file is missing from disk; removed it from the index");
        throw new ApiException(
            ErrorCode.CHUNK_NOT_FOUND, "No copy of this chunk is stored here", e);
      }
      if (!Checksums.sha256Hex(bytes).equals(file.checksum())) {
        quarantine(id, file);
        throw new ApiException(
            ErrorCode.STORED_CHECKSUM_MISMATCH,
            "The stored copy failed verification and has been quarantined");
      }
      return new VerifiedChunk(bytes, file.checksum());
    } catch (IOException e) {
      throw storageUnavailable(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes every servable and quarantined copy of a chunk. Deleting a chunk that is not stored
   * succeeds, so repeating a delete is safe.
   *
   * @param id the chunk's ID
   * @throws ApiException {@link ErrorCode#STORAGE_UNAVAILABLE} if a disk operation fails, in which
   *     case the delete is not confirmed
   */
  public void delete(ChunkId id) {
    ReentrantLock lock = lockFor(id);
    lock.lock();
    try {
      Path dir = directoryFor(id);
      if (Files.isDirectory(dir)) {
        deleteCopies(id, dir, null);
        forceDirectory(dir);
      }
      index.remove(id);
      log.atInfo()
          .addKeyValue("operation", "delete")
          .addKeyValue("chunk_id", id.value())
          .log("Deleted chunk at the controller's request");
    } catch (IOException e) {
      throw storageUnavailable(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the servable chunks, from the index rather than the disk.
   *
   * @return each chunk's ID, size and recorded checksum, sorted by chunk ID
   */
  public List<ChunkInfo> chunks() {
    return index.entrySet().stream()
        .map(e -> new ChunkInfo(e.getKey().value(), e.getValue().size(), e.getValue().checksum()))
        .sorted(Comparator.comparing(ChunkInfo::chunkId))
        .toList();
  }

  /**
   * Returns the chunks that have a quarantined copy, by scanning the disk.
   *
   * @return the chunk IDs, sorted and without duplicates
   * @throws UncheckedIOException if the store cannot be scanned, so a caller never reports a
   *     partial list
   */
  public List<String> quarantined() {
    try (Stream<Path> files = Files.walk(root, 3)) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith(CORRUPT_SUFFIX))
          .map(name -> name.substring(0, name.indexOf('.')))
          .filter(ChunkId::isValid)
          .distinct()
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot scan " + root + " for quarantined chunks", e);
    }
  }

  /** {@return the number of servable chunks} */
  public int chunkCount() {
    return index.size();
  }

  /**
   * Copies the body into the temp file, hashing it on the way, and forces the file to disk once its
   * size and checksum are known to be right.
   *
   * @return the body's size in bytes
   */
  private static long writeVerified(ChunkId id, InputStream body, Path temp, String checksum)
      throws IOException {
    MessageDigest digest = Checksums.newSha256();
    byte[] buffer = new byte[64 * 1024];
    long size = 0;
    try (FileChannel out = FileChannel.open(temp, WRITE)) {
      int read;
      while ((read = readBody(body, buffer)) != -1) {
        size += read;
        // Count the bytes rather than trust Content-Length, and stop as soon as there are too many.
        if (size > CHUNK_SIZE) {
          throw new ApiException(
              ErrorCode.INVALID_CHUNK_SIZE, "A chunk holds at most " + CHUNK_SIZE + " bytes");
        }
        digest.update(buffer, 0, read);
        out.write(ByteBuffer.wrap(buffer, 0, read));
      }
      if (size == 0) {
        throw new ApiException(ErrorCode.INVALID_CHUNK_SIZE, "A chunk holds at least 1 byte");
      }
      if (!Checksums.toHex(digest.digest()).equals(checksum)) {
        // Bytes damaged on the way in, or a sender bug: worth a WARN even though it is a 400.
        log.atWarn()
            .addKeyValue("operation", "put")
            .addKeyValue("chunk_id", id.value())
            .log("Body did not match its checksum header; nothing was stored");
        throw new ApiException(
            ErrorCode.BODY_CHECKSUM_MISMATCH,
            "The body does not match X-Checksum-SHA256; nothing was stored");
      }
      out.force(true);
    }
    return size;
  }

  /**
   * Reads the next part of the request body. A failure here is the client's, not the disk's, so it
   * becomes {@link ErrorCode#INVALID_REQUEST} rather than {@link ErrorCode#STORAGE_UNAVAILABLE}.
   */
  private static int readBody(InputStream body, byte[] buffer) {
    try {
      return body.read(buffer);
    } catch (IOException e) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, "The request body could not be read; nothing was stored", e);
    }
  }

  /**
   * Renames a verified temp file into place under the chunk's lock, then removes every other copy
   * of the chunk and forces the directory.
   */
  private void publish(ChunkId id, Path temp, StoredFile file) throws IOException {
    ReentrantLock lock = lockFor(id);
    lock.lock();
    try {
      Files.move(temp, file.path(), ATOMIC_MOVE);
      // Index the new copy before anything else can fail, so the index always matches the disk.
      index.put(id, file);
      deleteCopies(id, file.path().getParent(), file.path());
      forceDirectory(file.path().getParent());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Renames a copy that failed verification to {@code <name>.corrupt} and drops it from the index.
   * The caller holds the chunk's lock.
   */
  private void quarantine(ChunkId id, StoredFile file) throws IOException {
    Path corrupt = file.path().resolveSibling(file.path().getFileName() + CORRUPT_SUFFIX);
    Files.move(file.path(), corrupt, ATOMIC_MOVE);
    index.remove(id);
    forceDirectory(file.path().getParent());
    log.atWarn()
        .addKeyValue("operation", "get")
        .addKeyValue("chunk_id", id.value())
        .log("Stored copy failed verification; quarantined it as {}", corrupt.getFileName());
  }

  /**
   * Deletes the chunk's servable and quarantined files in {@code dir}. Temp files are left alone:
   * they belong to writes in progress, and boot removes any that are left over.
   *
   * @param keep the file to keep, or {@code null} to delete every copy
   */
  private static void deleteCopies(ChunkId id, Path dir, Path keep) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, id + ".*")) {
      for (Path file : files) {
        if (!file.equals(keep) && !file.getFileName().toString().endsWith(TEMP_SUFFIX)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  /** Rebuilds the index from file names at boot, and deletes writes that never finished. */
  private void loadIndex() throws IOException {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root, 3)) {
      files = walk.filter(Files::isRegularFile).toList();
    }
    int unfinished = 0;
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (name.endsWith(TEMP_SUFFIX)) {
        Files.delete(file);
        unfinished++;
      } else if (!name.endsWith(CORRUPT_SUFFIX)) {
        indexAtBoot(file, name);
      }
    }
    log.atInfo()
        .addKeyValue("operation", "boot")
        .log(
            "Opened the chunk store: {} chunks, {} unfinished writes deleted",
            index.size(),
            unfinished);
  }

  /**
   * Indexes one file found at boot, or logs it and leaves it alone if it is not a chunk file in its
   * own chunk's directory.
   */
  private void indexAtBoot(Path file, String name) throws IOException {
    int dot = name.lastIndexOf('.');
    String idPart = dot > 0 ? name.substring(0, dot) : "";
    String checksum = name.substring(dot + 1);
    if (!ChunkId.isValid(idPart)
        || !Checksums.isSha256Hex(checksum)
        || !file.getParent().equals(directoryFor(new ChunkId(idPart)))) {
      log.atWarn()
          .addKeyValue("operation", "boot")
          .log("Left alone a file that is not a chunk file: {}", file);
      return;
    }
    ChunkId id = new ChunkId(idPart);
    StoredFile found = new StoredFile(file, Files.size(file), checksum);
    StoredFile indexed = index.get(id);
    if (indexed == null) {
      index.put(id, found);
      return;
    }
    // Two servable copies can only remain after a crash in the middle of a PUT. Serve the newer
    // one; the next write or delete of the chunk removes the other.
    boolean foundIsNewer = isNewer(found, indexed);
    StoredFile newer = foundIsNewer ? found : indexed;
    StoredFile older = foundIsNewer ? indexed : found;
    index.put(id, newer);
    log.atWarn()
        .addKeyValue("operation", "boot")
        .addKeyValue("chunk_id", id.value())
        .log(
            "Two servable copies of one chunk; serving {} and keeping {}",
            newer.path(),
            older.path());
  }

  /**
   * Returns whether {@code a} is newer than {@code b}: by modification time, or on a tie by the
   * greater file name, so boot always picks the same copy.
   */
  private static boolean isNewer(StoredFile a, StoredFile b) throws IOException {
    int byTime = Files.getLastModifiedTime(a.path()).compareTo(Files.getLastModifiedTime(b.path()));
    return byTime != 0 ? byTime > 0 : a.path().getFileName().compareTo(b.path().getFileName()) > 0;
  }

  /**
   * Returns the chunk's directory, {@code <aa>/<bb>}: the first 4 hex characters of SHA-256 of the
   * chunk ID. Chunk IDs start with a UUID v7 timestamp, so their own prefix would put nearly every
   * file in one directory.
   */
  private Path directoryFor(ChunkId id) {
    String hash = Checksums.sha256Hex(id.value().getBytes(US_ASCII));
    return root.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4));
  }

  /**
   * Creates a missing directory, then forces its parent and the store root, so the new directory
   * itself survives a crash along with the files later published in it.
   */
  private void createDirectory(Path dir) throws IOException {
    if (Files.isDirectory(dir)) {
      return;
    }
    Files.createDirectories(dir);
    forceDirectory(dir.getParent());
    forceDirectory(root);
  }

  /** Returns the lock stripe that guards a chunk. */
  private ReentrantLock lockFor(ChunkId id) {
    return locks[Math.floorMod(id.hashCode(), LOCK_STRIPES)];
  }

  /** Forces a directory's entries to disk, which makes a rename or delete inside it durable. */
  private static void forceDirectory(Path dir) throws IOException {
    try (FileChannel channel = FileChannel.open(dir, READ)) {
      channel.force(true);
    }
  }

  /**
   * Deletes a temp file after a write. A failure is logged rather than thrown: the write has
   * already finished one way or the other, and boot deletes any temp file left behind.
   */
  private static void deleteTempFile(Path temp) {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException e) {
      log.atWarn()
          .setCause(e)
          .log("Could not delete temp file {}; the next boot deletes it", temp.getFileName());
    }
  }

  /** Wraps a disk failure as {@link ErrorCode#STORAGE_UNAVAILABLE}, keeping it as the cause. */
  private static ApiException storageUnavailable(IOException cause) {
    return new ApiException(
        ErrorCode.STORAGE_UNAVAILABLE, "The worker could not use its storage", cause);
  }
}
