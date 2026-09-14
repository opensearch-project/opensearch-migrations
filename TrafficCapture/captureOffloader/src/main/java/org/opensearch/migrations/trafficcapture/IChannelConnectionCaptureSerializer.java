package org.opensearch.migrations.trafficcapture;

import java.util.function.Consumer;

import io.netty.util.concurrent.EventExecutor;

public interface IChannelConnectionCaptureSerializer<T> extends IChannelConnectionCaptureListener<T> {
    /**
     * Binds mutable connection-capture state and its periodic record flushes to the Netty event
     * loop that owns the connection.
     */
    void bindToConnectionEventLoop(
        EventExecutor eventLoop,
        Consumer<Throwable> asynchronousFailureHandler
    );

    /**
     * Verifies that the acknowledgement for a fully captured Critical Mutation Traffic request
     * still permits that request to be forwarded to the source.
     *
     * @throws RuntimeException when required capture is no longer authoritative
     */
    void validateCriticalMutationTrafficAcknowledgement(T acknowledgement);
}
