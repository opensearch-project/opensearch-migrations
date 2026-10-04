package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

/**
 * Defines the capture side of planned proxy shutdown. Orderly retirement first removes eligibility
 * for new captured connections, then lets already accepted connections finish and retires the
 * writer state that proves their records are complete.
 *
 * <p>The returned future represents that durable drain, not general process cleanup. Closing the
 * underlying sink before it completes could strand accepted traffic or end a writer's heartbeat
 * history before its connections have reached a terminal record.
 */
public interface IOrderlyRetirableCaptureFactory {
    /**
     * Stops accepting new captured connections before returning, then completes after existing
     * connections and capture-writer state are safely retired.
     */
    CompletableFuture<Void> retireForOrderlyShutdown();
}
