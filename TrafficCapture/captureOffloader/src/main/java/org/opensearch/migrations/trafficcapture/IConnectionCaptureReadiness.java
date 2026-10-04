package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

/**
 * Represents the startup barrier between constructing capture resources and accepting source
 * connections. Readiness means the capture path has completed the checks and durable
 * initialization required to assign an authoritative route immediately; it is stronger than
 * merely having allocated clients or opened network connections.
 *
 * <p>The future completes once for the factory's lifetime. Exceptional completion means opening
 * the source listener would create an untracked capture gap and must be treated as startup failure.
 */
public interface IConnectionCaptureReadiness {
    CompletableFuture<Void> readyForConnections();
}
