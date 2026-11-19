package io.github.rohitangshu2026.objstore.controller.object;

/**
 * A download that failed after its headers were sent. No status can be sent any more, so the
 * response is aborted and the client sees a body shorter than {@code Content-Length}. The failure
 * has already been logged where it was detected.
 */
public final class DownloadAbortedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed, for the server's logs only
   */
  public DownloadAbortedException(String message) {
    super(message);
  }
}
