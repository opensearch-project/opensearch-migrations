package org.opensearch.migrations.transform.shim.validation;

import java.time.Duration;
import java.util.Map;

/**
 * The result of dispatching a request to a single target.
 * On error, {@code statusCode} is -1 and {@code error} is non-null.
 * Latency is broken down into request transform, cluster, and response transform time.
 */
public record TargetResponse(
    String targetName,
    int statusCode,
    byte[] rawBody,
    Map<String, Object> parsedBody,
    Duration latency,
    Duration requestTransformLatency,
    Duration responseTransformLatency,
    Throwable error
) {
    /** Create an error response. */
    public static TargetResponse error(String targetName, Duration latency, Throwable error) {
        return new TargetResponse(targetName, -1, null, null, latency, Duration.ZERO, Duration.ZERO, error);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** Cluster latency = total - request transform - response transform. */
    public Duration clusterLatency() {
        return latency.minus(requestTransformLatency).minus(responseTransformLatency);
    }
}
