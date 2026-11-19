package io.github.rohitangshu2026.objstore.controller.workerclient;

/** A failed call to a worker, classified by {@link Failure} so callers never inspect raw errors. */
public final class WorkerCallException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Failure failure;
  private final String workerId;
  private final String operation;

  /**
   * Creates the exception.
   *
   * @param failure how the call failed
   * @param workerId the worker that was called
   * @param operation what was asked of it, for example {@code inventory}
   * @param cause the underlying error
   */
  public WorkerCallException(Failure failure, String workerId, String operation, Throwable cause) {
    super(operation + " on " + workerId + " failed: " + failure, cause);
    this.failure = failure;
    this.workerId = workerId;
    this.operation = operation;
  }

  /** {@return how the call failed} */
  public Failure failure() {
    return failure;
  }

  /** {@return the worker that was called} */
  public String workerId() {
    return workerId;
  }

  /** {@return what was asked of the worker} */
  public String operation() {
    return operation;
  }
}
