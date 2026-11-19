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
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaLocation;
import io.github.rohitangshu2026.objstore.controller.metadata.ReplicaRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Reads object metadata and lists objects ({@code docs/api.md} §3). */
@Service
public class ObjectService {

  private final ObjectRepository objects;
  private final ReplicaRepository replicas;

  /**
   * Creates the service.
   *
   * @param objects the objects and chunks
   * @param replicas the chunk copies
   */
  public ObjectService(ObjectRepository objects, ReplicaRepository replicas) {
    this.objects = objects;
    this.replicas = replicas;
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
