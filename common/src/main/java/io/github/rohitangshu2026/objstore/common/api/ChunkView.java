package io.github.rohitangshu2026.objstore.common.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One chunk of an object in its metadata. Serialized in snake_case.
 *
 * @param chunkId the chunk's ID
 * @param index the chunk's position in the object, from 0
 * @param size the chunk's size in bytes
 * @param checksumSha256 the chunk's SHA-256
 * @param replicas where the chunk's copies are, sorted by worker ID
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ChunkView(
    String chunkId, int index, int size, String checksumSha256, List<ReplicaView> replicas) {}
