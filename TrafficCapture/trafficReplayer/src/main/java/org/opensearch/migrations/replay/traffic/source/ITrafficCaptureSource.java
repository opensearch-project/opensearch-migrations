package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    enum CommitResult {
        Immediate, AfterNextRead, BlockedByOtherCommits, Ignored
    }

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(IInstrumentationAttributes context);

    /**
     * Returns true if the committed results are immediate
     */
    CommitResult commitTrafficStream(IInstrumentationAttributes context,
                                     ITrafficStreamKey trafficStreamKey) throws IOException;

    default void close() throws IOException {}

    /**
     * Keep-alive call to be used by the BlockingTrafficSource to keep this connection alive if
     * this is required.
     */
    default void touch(IInstrumentationAttributes context) {}

    /**
     * @return The time that the next call to touch() must be completed for this source to stay
     * active.  Empty indicates that touch() does not need to be called to keep the
     * source active.
     */
    default Optional<Instant> getNextRequiredTouch() { return Optional.empty(); }
}
