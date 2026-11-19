package io.github.rohitangshu2026.objstore.common.error;

/**
 * The JSON body of every error response: {@code {"error": "<code>", "message": "<text>"}}.
 *
 * @param error the stable error code, from {@link ErrorCode#code()}
 * @param message a human-readable explanation that is safe to show the client
 */
public record ErrorBody(String error, String message) {}
