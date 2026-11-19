package io.github.rohitangshu2026.objstore.common.error;

/**
 * A failure that the client should see, carrying an {@link ErrorCode} and a message.
 *
 * <p>Services throw it for every expected client-visible failure, and each application's exception
 * handler turns it into an {@link ErrorBody} with the code's HTTP status. The message must be safe
 * to show a client (rule N8): no stack traces, file paths or raw library text. The cause, when
 * there is one, is only ever logged.
 */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;

  /**
   * Creates an exception for a failure with no underlying cause.
   *
   * @param code the error code, which decides the HTTP status
   * @param message a message that is safe to show the client
   */
  public ApiException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * Creates an exception for a failure caused by another one.
   *
   * @param code the error code, which decides the HTTP status
   * @param message a message that is safe to show the client
   * @param cause the underlying failure, which is logged but never sent to the client
   */
  public ApiException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** {@return the error code to send to the client} */
  public ErrorCode code() {
    return code;
  }
}
