package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * How well the chunks of committed objects are replicated.
 *
 * @param total every chunk of a committed object
 * @param healthy chunks with 2 or more HEALTHY copies on ALIVE workers
 * @param underReplicated chunks with exactly 1
 * @param unavailable chunks with none
 */
public record ReplicationCounts(long total, long healthy, long underReplicated, long unavailable) {}
