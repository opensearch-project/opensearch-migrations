package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;

public interface IChannelConnectionCaptureSerializer<T> {
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

    void addCloseEvent(Instant timestamp) throws IOException;

    void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException;

    void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException;

    void addInterimResponseEvent(Instant timestamp, ByteBuf buffer) throws IOException;

    void addExceptionCaughtEvent(Instant timestamp, Throwable throwable) throws IOException;

    void addEndOfFirstLineIndicator(int characterIndex) throws IOException;

    void addEndOfHeadersIndicator(int characterIndex) throws IOException;

    void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException;

    CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException;

    void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException;
}
