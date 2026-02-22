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
     * Called when a connection is fully done (closed or expired by the accumulator).
     * Implementations may use this to clean up per-connection tracking state.
     */
    default void onConnectionDone(ITrafficStreamKey trafficStreamKey) {}

    /**
     * Called when a ConnectionReplaySession's channel is closed (regardless of cause).
     * Implementations may use this to decrement outstanding synthetic close counters.
     */
    default void onSessionClosed(String connectionId, int sessionNumber, int generation) {}

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
