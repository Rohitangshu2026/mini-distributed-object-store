package io.github.rohitangshu2026.objstore.controller.metadata;

/**
 * Why a copy of a chunk is to be deleted. A tombstone is the only route by which any copy is ever
 * deleted (rule N5).
 */
public enum TombstoneReason {

  /** The object was deleted. */
  OBJECT_DELETED,

  /** The upload was aborted before it committed. */
  UPLOAD_ABORTED,

  /** A write failed or its outcome is unknown, so the copy may exist. */
  WRITE_FAILED,

  /** A repair was still writing the copy when the controller restarted. */
  REPAIR_INTERRUPTED,

  /** The chunk has two HEALTHY copies elsewhere, so this lost or corrupt copy is surplus. */
  EXTRA_COPY,

  /** A worker holds a copy of an existing object's chunk that the metadata does not record. */
  UNTRACKED_COPY,

  /**
   * A worker still holds, past the grace period, a copy of a chunk whose object no longer exists.
   */
  ORPHAN
}
