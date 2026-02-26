package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.Map;

/**
 * The result of dispatching a request to a single target.
 * On error, {@code statusCode} is -1 and {@code error} is non-null.
 */
public record TargetResponse(
    String targetName,
    int statusCode,
    byte[] rawBody,
    Map<String, Object> parsedBody,
    Duration latency,
    Throwable error
) {
    /** Create an error response. */
    public static TargetResponse error(String targetName, Duration latency, Throwable error) {
        return new TargetResponse(targetName, -1, null, null, latency, error);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
