package io.github.rohitangshu2026.objstore.controller.metadata;

/** Where an object is in its lifecycle ({@code docs/architecture.md} §8). Stored by name. */
public enum ObjectStatus {

  /** Accepted and being uploaded; its size, checksum and chunk count are not known yet. */
  UPLOADING,

  /** Every chunk has two HEALTHY copies, so the object can be read. */
  COMMITTED,

  /** Being removed after a delete or an aborted upload; it can no longer be read. */
  DELETING
}
