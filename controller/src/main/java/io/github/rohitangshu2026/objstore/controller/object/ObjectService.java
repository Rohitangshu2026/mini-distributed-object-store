package io.github.rohitangshu2026.objstore.controller.object;

import io.github.rohitangshu2026.objstore.common.Timestamps;
import io.github.rohitangshu2026.objstore.common.api.ChunkView;
import io.github.rohitangshu2026.objstore.common.api.ObjectList;
import io.github.rohitangshu2026.objstore.common.api.ObjectListEntry;
import io.github.rohitangshu2026.objstore.common.api.ObjectMetadata;
import io.github.rohitangshu2026.objstore.common.api.ReplicaView;
import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectRow;
import io.github.rohitangshu2026.objstore.controller.metadata.ObjectStatus;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaLocation;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import io.github.rohitangshu2026.objstore.controller.metadata.TombstoneReason;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Reads, changes and deletes objects other than by upload ({@code docs/api.md} §3). */
@Service
public class ObjectService {

  private static final Logger log = LoggerFactory.getLogger(ObjectService.class);

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;
  private final ObjectRemoval removal;
  private final InFlightObjects inFlight;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param objects the objects and chunks
   * @param replicas the chunk copies
   * @param removal deletes objects
   * @param inFlight the objects requests are working on, which recovery leaves alone
   * @param transactions the transactions around each change
   * @param clock the source of timestamps
   */
  public ObjectService(
      ObjectRepository objects,
      ReplicaRepository replicas,
      ObjectRemoval removal,
      InFlightObjects inFlight,
      TransactionTemplate transactions,
      Clock clock) {
    this.objects = objects;
    this.replicas = replicas;
    this.removal = removal;
    this.inFlight = inFlight;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Adds or updates custom metadata keys of a committed object; keys are never removed. Reading,
   * merging, checking the merged map and writing happen in one transaction, which begins IMMEDIATE,
   * so two concurrent PATCHes run one after the other and neither loses its keys.
   *
   * @param objectId the object's ID
   * @param patchJson the keys to add or update, a flat JSON object
   * @return the object's metadata after the change
   * @throws ApiException {@link ErrorCode#INVALID_METADATA} if the body or the merged map breaks a
   *     rule, {@link ErrorCode#OBJECT_NOT_FOUND}, or {@link ErrorCode#OBJECT_NOT_COMMITTED}
   */
  public ObjectMetadata patch(String objectId, String patchJson) {
    ObjectNode patch = CustomMetadata.parse(patchJson);
    transactions.executeWithoutResult(status -> {
      ObjectRow object = committed(objectId);
      ObjectNode merged = CustomMetadata.fromStored(object.customMetadata());
      merged.setAll(patch);
      objects.updateCustomMetadata(objectId, CustomMetadata.toStored(merged), clock.millis());
    });
    return metadata(objectId);
  }

  /**
   * Deletes a committed object (D1 to D3). Every copy is deleted or left as a durable tombstone,
   * which the tombstone job finishes once its worker is ALIVE, so a worker that is down never
   * blocks a delete.
   *
   * @param objectId the object's ID
   * @throws ApiException {@link ErrorCode#OBJECT_NOT_FOUND}, or {@link
   *     ErrorCode#OBJECT_NOT_COMMITTED} if it is uploading or already being deleted
   */
  public void delete(String objectId) {
    // Before D1, so recovery never finishes this delete while the request is still running.
    inFlight.add(objectId);
    try {
      if (!removal.begin(objectId, ObjectStatus.COMMITTED, TombstoneReason.OBJECT_DELETED)) {
        ObjectRow object =
            objects
                .find(objectId)
                .orElseThrow(() ->
                    new ApiException(ErrorCode.OBJECT_NOT_FOUND, "No object with ID " + objectId));
        // Never continue without D1: even an upload that committed a moment ago gets a 409.
        throw new ApiException(
            ErrorCode.OBJECT_NOT_COMMITTED, "Object " + objectId + " is " + object.status());
      }
      int deleted = removal.attemptDeletes(objectId);
      removal.finish(objectId, TombstoneReason.OBJECT_DELETED);
      log.atInfo()
          .addKeyValue("operation", "delete")
          .addKeyValue("object_id", objectId)
          .log("Object deleted by client request; {} copies deleted now", deleted);
    } finally {
      inFlight.remove(objectId);
    }
  }

  /** Returns the object if it is COMMITTED, and otherwise throws the matching 404 or 409. */
  private ObjectRow committed(String objectId) {
    ObjectRow object =
        objects
            .find(objectId)
            .orElseThrow(() ->
                new ApiException(ErrorCode.OBJECT_NOT_FOUND, "No object with ID " + objectId));
    if (object.status() != ObjectStatus.COMMITTED) {
      throw new ApiException(
          ErrorCode.OBJECT_NOT_COMMITTED, "Object " + objectId + " is " + object.status());
    }
    return object;
  }

  /**
   * Returns an object's system and custom metadata, with its chunks and where their copies are. An
   * object in any status is returned.
   *
   * @param objectId the object's ID
   * @return the metadata
   * @throws ApiException {@link ErrorCode#OBJECT_NOT_FOUND} if there is no such object
   */
  public ObjectMetadata metadata(String objectId) {
    ObjectRow object =
        objects
            .find(objectId)
            .orElseThrow(() ->
                new ApiException(ErrorCode.OBJECT_NOT_FOUND, "No object with ID " + objectId));
    Map<String, List<ReplicaView>> copies =
        replicas.locationsOf(objectId).stream()
            .collect(
                Collectors.groupingBy(
                    ReplicaLocation::chunkId,
                    Collectors.mapping(
                        copy ->
                            new ReplicaView(
                                copy.workerId(),
                                copy.status().name(),
                                copy.lastVerifiedAt() == null
                                    ? null
                                    : Timestamps.format(copy.lastVerifiedAt())),
                        Collectors.toList())));
    List<ChunkView> chunks =
        objects.chunksOf(objectId).stream()
            .map(chunk ->
                new ChunkView(
                    chunk.chunkId(),
                    chunk.chunkIndex(),
                    chunk.size(),
                    chunk.checksumSha256(),
                    copies.getOrDefault(chunk.chunkId(), List.of())))
            .toList();
    return new ObjectMetadata(
        object.objectId(),
        object.name(),
        object.size(),
        object.contentType(),
        object.checksumSha256(),
        object.chunkCount(),
        object.status().name(),
        Timestamps.format(object.createdAt()),
        Timestamps.format(object.updatedAt()),
        CustomMetadata.fromStored(object.customMetadata()),
        chunks);
  }

  /**
   * Returns one page of the committed objects, oldest first.
   *
   * @param limit the most objects to return, 1 to 1000
   * @param offset how many objects to skip, 0 or more
   * @return the page and the total count
   */
  public ObjectList list(int limit, int offset) {
    List<ObjectListEntry> page =
        objects.listCommitted(limit, offset).stream()
            .map(object ->
                new ObjectListEntry(
                    object.objectId(),
                    object.name(),
                    object.size(),
                    object.contentType(),
                    Timestamps.format(object.createdAt())))
            .toList();
    // A second query, so the total may differ by a concurrent commit (docs/architecture.md §9).
    return new ObjectList(page, limit, offset, objects.countCommitted());
  }
}
