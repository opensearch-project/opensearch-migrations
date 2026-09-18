package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

/**
 * Supports the capture protocol required before a planned proxy shutdown may close its sink.
 */
public interface IOrderlyRetirableCaptureFactory {
    /**
     * Stops accepting new captured connections before returning, then completes after existing
     * connections and capture-writer state are safely retired.
     */
    CompletableFuture<Void> retireForOrderlyShutdown();
}
