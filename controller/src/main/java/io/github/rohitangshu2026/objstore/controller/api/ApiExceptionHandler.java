package io.github.rohitangshu2026.objstore.controller.api;

import io.github.rohitangshu2026.objstore.common.error.ApiException;
import io.github.rohitangshu2026.objstore.common.error.ErrorBody;
import io.github.rohitangshu2026.objstore.common.error.ErrorCode;
import io.github.rohitangshu2026.objstore.controller.workerclient.WorkerCallException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The controller's only exception handler (rule A8, {@code docs/architecture.md} §10.2).
 *
 * <p>Every error leaves as an {@link ErrorBody} with a stable code from {@code docs/api.md} and a
 * message that is safe for clients (rule N8); the details go only to the logs. Each failure is
 * logged once, here, at the level §10.2 gives it.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * Answers an expected failure with its own code and message.
   *
   * <p>Logs a 4xx at DEBUG, a 5xx with a cause at ERROR with its stack trace, and any other 5xx at
   * WARN.
   *
   * @param e the failure
   * @param request the request that failed, named in the log
   * @return the error response
   */
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> handle(ApiException e, HttpServletRequest request) {
    ErrorCode code = e.code();
    if (code.status() < 500) {
      log.atDebug().addKeyValue("operation", operation(request)).log(e.getMessage());
    } else if (e.getCause() instanceof WorkerCallException failed) {
      // A worker that cannot be reached is expected now and then; it degrades, nothing is broken.
      log.atWarn()
          .addKeyValue("operation", operation(request))
          .addKeyValue("worker_id", failed.workerId())
          .log("{}: {}", e.getMessage(), failed.failure());
    } else if (e.getCause() != null) {
      // Something underneath actually broke, such as the database, so data may be at risk.
      log.atError().setCause(e).addKeyValue("operation", operation(request)).log(e.getMessage());
    } else {
      log.atWarn().addKeyValue("operation", operation(request)).log(e.getMessage());
    }
    return error(code, e.getMessage());
  }

  /**
   * Answers Spring's own rejections and any unexpected exception.
   *
   * <p>An unknown path becomes 404 {@code not_found}, a wrong method 405 {@code
   * method_not_allowed}, and any other client error 400 {@code invalid_request}. Anything else is a
   * bug: 500 {@code internal_error}, logged at ERROR with its stack trace.
   *
   * @param e the exception
   * @param request the request that failed, named in the log
   * @param response the response, which may already have been started
   * @return the error response, or {@code null} if the client has disconnected and nobody is left
   *     to answer
   * @throws Exception {@code e} itself if the response has already started, so it is aborted
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> handle(
      Exception e, HttpServletRequest request, HttpServletResponse response) throws Exception {
    if (response.isCommitted()) {
      // The headers are gone, so no status can be sent: rethrowing lets Tomcat abort the
      // connection, and the client sees a body shorter than Content-Length. A failed download
      // was logged where it was detected.
      log.atDebug().addKeyValue("operation", operation(request)).log("Aborting a started response");
      throw e;
    }
    if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
      log.atDebug().addKeyValue("operation", operation(request)).log("Client disconnected");
      return null;
    }
    if (e instanceof HttpMessageNotReadableException || e instanceof MultipartException) {
      // A malformed JSON or multipart body; Spring does not map these to a status itself.
      log.atDebug().addKeyValue("operation", operation(request)).log(e.getMessage());
      return error(
          ErrorCode.INVALID_REQUEST, "The request body is not valid JSON for this endpoint");
    }
    if (e instanceof ErrorResponse rejected && rejected.getStatusCode().is4xxClientError()) {
      log.atDebug().addKeyValue("operation", operation(request)).log(e.getMessage());
      return switch (rejected.getStatusCode().value()) {
        case 404 -> error(ErrorCode.NOT_FOUND, "No endpoint at this path");
        case 405 ->
            ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                // Keep Spring's Allow header, which lists the methods the endpoint does support.
                .headers(rejected.getHeaders())
                .body(
                    new ErrorBody(
                        ErrorCode.METHOD_NOT_ALLOWED.code(),
                        "This endpoint does not support that HTTP method"));
        default -> error(ErrorCode.INVALID_REQUEST, "The request is malformed");
      };
    }
    log.atError()
        .setCause(e)
        .addKeyValue("operation", operation(request))
        .log("Unexpected failure");
    return error(ErrorCode.INTERNAL_ERROR, "Unexpected failure; see the controller's logs");
  }

  /** Builds an error response with the code's status. */
  private static ResponseEntity<ErrorBody> error(ErrorCode code, String message) {
    return ResponseEntity.status(code.status()).body(new ErrorBody(code.code(), message));
  }

  /** Names a request for the log, for example {@code GET /internal/chunks/…}. */
  private static String operation(HttpServletRequest request) {
    return request.getMethod() + " " + request.getRequestURI();
  }
}
