package io.github.rohitangshu2026.objstore.controller.workerclient;

/**
 * How a call to a worker failed, which decides what the caller may do next ({@code
 * docs/architecture.md} §10.1).
 */
public enum Failure {

  /** The call did not happen: the worker could not be reached or refused the work. Fail over. */
  UNREACHABLE,

  /** The call may have happened: the answer was lost or the worker failed mid-way. */
  OUTCOME_UNKNOWN,

  /** The worker has no servable copy of the chunk. */
  NOT_FOUND,

  /** The worker found its copy corrupt and quarantined it. */
  CORRUPT,

  /** The worker rejected the request as invalid, which is a controller bug. */
  REJECTED
}
