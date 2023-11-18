package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk();

    default void commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {}

    default void close() throws IOException {}

    /**
     * Keep-alive call to be used by the BlockingTrafficSource to keep this connection alive if
     * this is required.
     * @return The time that the next call to touch() must be completed for this source to stay
     * active.  Empty indicates that touch() does not need to be called to keep the
     * source active.
     */
    default Optional<Instant> touch() {
        return Optional.empty();
    }
}
