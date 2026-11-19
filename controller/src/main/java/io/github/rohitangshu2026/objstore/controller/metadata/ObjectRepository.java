package io.github.rohitangshu2026.objstore.controller.metadata;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes objects and their chunks.
 *
 * <p>Callers own the transaction boundaries: a change that spans several statements runs inside the
 * caller's {@code TransactionTemplate}, and a read on its own is one autocommit statement. Every
 * method throws {@link org.springframework.dao.DataAccessException} if the database rejects the
 * statement, for example because it breaks a schema constraint.
 */
@Repository
public class ObjectRepository {

  private static final String OBJECT_COLUMNS =
      "object_id, name, size, content_type, checksum_sha256, chunk_count, status, created_at,"
          + " updated_at, custom_metadata";
  private static final String CHUNK_COLUMNS =
      "chunk_id, object_id, chunk_index, size, checksum_sha256";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the client for the metadata database
   */
  public ObjectRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts an object.
   *
   * @param object the new object
   */
  public void insert(ObjectRow object) {
    jdbc.sql(
            "INSERT INTO objects ("
                + OBJECT_COLUMNS
                + ") VALUES (:objectId, :name, :size, :contentType, :checksumSha256, :chunkCount,"
                + " :status, :createdAt, :updatedAt, :customMetadata)")
        .param("objectId", object.objectId())
        .param("name", object.name())
        .param("size", object.size())
        .param("contentType", object.contentType())
        .param("checksumSha256", object.checksumSha256())
        .param("chunkCount", object.chunkCount())
        .param("status", object.status().name())
        .param("createdAt", object.createdAt())
        .param("updatedAt", object.updatedAt())
        .param("customMetadata", object.customMetadata())
        .update();
  }

  /**
   * Finds an object by its ID.
   *
   * @param objectId the object's ID
   * @return the object, or empty if there is none
   */
  public Optional<ObjectRow> find(String objectId) {
    return jdbc.sql("SELECT " + OBJECT_COLUMNS + " FROM objects WHERE object_id = ?")
        .param(objectId)
        .query(ObjectRow.class)
        .optional();
  }

  /**
   * Replaces an object's custom metadata and records when it changed.
   *
   * <p>A read-modify-write such as PATCH must read the object and call this inside one transaction.
   * Every transaction takes the write lock when it begins, so two such updates run one after the
   * other and neither overwrites the other.
   *
   * @param objectId the object's ID
   * @param customMetadata the new metadata, as a JSON object
   * @param updatedAt when it changed, in epoch milliseconds
   * @return {@code true} if the object exists and was updated
   */
  public boolean updateCustomMetadata(String objectId, String customMetadata, long updatedAt) {
    return jdbc.sql(
                "UPDATE objects SET custom_metadata = :customMetadata, updated_at = :updatedAt"
                    + " WHERE object_id = :objectId")
            .param("customMetadata", customMetadata)
            .param("updatedAt", updatedAt)
            .param("objectId", objectId)
            .update()
        == 1;
  }

  /**
   * Inserts a chunk of an existing object.
   *
   * @param chunk the new chunk
   */
  public void insertChunk(ChunkRow chunk) {
    jdbc.sql(
            "INSERT INTO chunks ("
                + CHUNK_COLUMNS
                + ") VALUES (:chunkId, :objectId, :chunkIndex, :size, :checksumSha256)")
        .param("chunkId", chunk.chunkId())
        .param("objectId", chunk.objectId())
        .param("chunkIndex", chunk.chunkIndex())
        .param("size", chunk.size())
        .param("checksumSha256", chunk.checksumSha256())
        .update();
  }

  /**
   * Returns an object's chunks in order.
   *
   * @param objectId the object's ID
   * @return the chunks, sorted by chunk index; empty if the object has none or does not exist
   */
  public List<ChunkRow> chunksOf(String objectId) {
    return jdbc.sql(
            "SELECT " + CHUNK_COLUMNS + " FROM chunks WHERE object_id = ? ORDER BY chunk_index")
        .param(objectId)
        .query(ChunkRow.class)
        .list();
  }
}
