package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import io.netty.buffer.ByteBuf;

/**
 * Plain Java test implementation whose methods deliberately do nothing unless a test overrides
 * them.
 */
abstract class NoopChannelConnectionCaptureSerializer<T>
    implements IChannelConnectionCaptureSerializer<T> {

    @Override
    public void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {}

    @Override
    public void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local)
        throws IOException {}

    @Override
    public void addDisconnectEvent(Instant timestamp) throws IOException {}

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {}

    @Override
    public void addDeregisterEvent(Instant timestamp) throws IOException {}

    @Override
    public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    @Override
    public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    @Override
    public void addFlushEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelRegisteredEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelUnregisteredEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelActiveEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelInactiveEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelReadEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelReadCompleteEvent(Instant timestamp) throws IOException {}

    @Override
    public void addUserEventTriggeredEvent(Instant timestamp) throws IOException {}

    @Override
    public void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException {}

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
