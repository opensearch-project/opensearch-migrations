package org.opensearch.migrations.replay.traffic.source;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;

public interface ITrafficCaptureSource extends AutoCloseable {

    enum CommitResult {
        IMMEDIATE,
        AFTER_NEXT_READ,
        BLOCKED_BY_OTHER_COMMITS,
        IGNORED
    }

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    );

    CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException;

    /**
     * Called by the accumulator when a connection's lifecycle is complete — either because a
     * source close observation was processed, the accumulation expired, or a synthetic
     * reassignment close was injected. Fires on the main thread.
     * <p>
     * This is an accumulator-level event: it means no more source traffic will be processed
     * for this connection. It does NOT mean the target-side Netty channel is closed yet.
     * Use this to clean up per-connection tracking state (e.g., {@code partitionToActiveConnections}).
     */
    default void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {}

    /**
     * Called when a {@code ConnectionReplaySession}'s Netty channel has been closed and its
     * cache entry invalidated, regardless of cause (source close, expiry, or synthetic
     * reassignment close). Fires on the Netty event loop thread.
     * <p>
     * This is a target-side event: it means the TCP connection to the target cluster is gone.
     * Use this to decrement {@code outstandingTrafficSourceReaderInterruptedCloseSessions} so the source knows it
     * is safe to resume returning real Kafka records.
     * <p>
     * Note: {@code onConnectionAccumulationComplete} fires first (accumulator-level), then this fires later
     * (after the Netty close completes). Both may fire for the same logical connection.
     */
    default void onNetworkConnectionClosed(String nodeId, String connectionId, int sessionNumber, int generation) {}

    default void close() throws Exception {}

    /**
     * Keep-alive call to be used by the BlockingTrafficSource to keep this connection alive if
     * this is required.
     */
    default void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {}

    /**
     * @return The time that the next call to touch() must be completed for this source to stay
     * active.  Empty indicates that touch() does not need to be called to keep the
     * source active.
     */
    default Optional<Instant> getNextRequiredTouch() {
        return Optional.empty();
    }
}
