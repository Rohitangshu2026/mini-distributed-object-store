package io.github.rohitangshu2026.objstore.controller.metadata;

/** The state of one copy of a chunk on one worker ({@code docs/architecture.md} §8). */
public enum ReplicaStatus {

  /** Recorded before the write to the worker, which may still be in progress (rule N4). */
  PENDING,

  /** The worker confirmed a write it had verified (rule N2), and nothing has gone wrong since. */
  HEALTHY,

  /** The worker is DEAD, or its inventory no longer lists the copy. */
  LOST,

  /** A read of the copy failed verification, or its checksum differs from the metadata. */
  CORRUPT
}
