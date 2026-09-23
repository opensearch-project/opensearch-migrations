package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;

/**
 * Plain Java test implementation whose methods deliberately do nothing unless a test overrides
 * them.
 */
abstract class NoopChannelConnectionCaptureSerializer<T>
    implements IChannelConnectionCaptureSerializer<T> {

    @Override
    public void bindToConnectionEventLoop(
        EventExecutor eventLoop,
        Consumer<Throwable> asynchronousFailureHandler
    ) {}

    @Override
    public void validateCriticalMutationTrafficAcknowledgement(T acknowledgement) {}

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {}

    @Override
    public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    @Override
    public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    @Override
    public void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {}

    @Override
    public void addEndOfFirstLineIndicator(int characterIndex) throws IOException {}

    @Override
    public void addEndOfHeadersIndicator(int characterIndex) throws IOException {}

    @Override
    public void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException {}

    @Override
    public CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException {}
}
