package io.github.rohitangshu2026.objstore.common.api;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One chunk as a worker reports it: the response to a chunk PUT, and each entry of the worker's
 * inventory. Serialized in snake_case: {@code chunk_id}, {@code size}, {@code checksum_sha256}.
 *
 * @param chunkId the chunk's ID in its canonical string form
 * @param size the chunk's size in bytes
 * @param checksumSha256 the chunk's SHA-256 as 64 lowercase hex characters. In an inventory, this
 *     is the checksum recorded in the file name, not one re-verified for the report.
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ChunkInfo(String chunkId, long size, String checksumSha256) {}
