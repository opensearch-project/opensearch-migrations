package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

/**
 * Exposes the point at which a capture factory can accept a source connection without failing its
 * initialization contract.
 */
public interface IConnectionCaptureReadiness {
    CompletableFuture<Void> readyForConnections();
}
