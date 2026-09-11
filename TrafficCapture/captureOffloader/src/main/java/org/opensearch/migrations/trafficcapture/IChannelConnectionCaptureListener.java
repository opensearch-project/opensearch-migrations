package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;

public interface IChannelConnectionCaptureListener<T> {
    void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException;

    void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException;

    void addDisconnectEvent(Instant timestamp) throws IOException;

    void addCloseEvent(Instant timestamp) throws IOException;

    void addDeregisterEvent(Instant timestamp) throws IOException;

    void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException;

    void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException;

    void addFlushEvent(Instant timestamp) throws IOException;

    void addChannelRegisteredEvent(Instant timestamp) throws IOException;

    void addChannelUnregisteredEvent(Instant timestamp) throws IOException;

    void addChannelActiveEvent(Instant timestamp) throws IOException;

    void addChannelInactiveEvent(Instant timestamp) throws IOException;

    void addChannelReadEvent(Instant timestamp) throws IOException;

    void addChannelReadCompleteEvent(Instant timestamp) throws IOException;

    void addUserEventTriggeredEvent(Instant timestamp) throws IOException;

    void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException;

    void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException;

    void addEndOfFirstLineIndicator(int characterIndex) throws IOException;

    void addEndOfHeadersIndicator(int characterIndex) throws IOException;

    /**
     * Adds an end-of-record indicator and resets the index values in preparation of any new
     * messages that may come across the channel.
     *
     * @param timestamp
     * @throws IOException
     */
    void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException;

    CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException;

    void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException;
}
