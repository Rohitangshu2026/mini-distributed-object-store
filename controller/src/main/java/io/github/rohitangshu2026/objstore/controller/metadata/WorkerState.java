package io.github.rohitangshu2026.objstore.controller.metadata;

/** What the controller believes about a worker's liveness ({@code docs/architecture.md} §8). */
public enum WorkerState {

  /** Heartbeats are arriving on time. */
  ALIVE,

  /** Heartbeats are late, or the controller has just started and not yet heard from the worker. */
  SUSPECT,

  /** Heartbeats stopped long enough ago that the worker's HEALTHY copies are treated as LOST. */
  DEAD
}
