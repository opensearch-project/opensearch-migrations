package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;

public interface IChannelConnectionCaptureListener<T> {
    default void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {}

    default void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException {}

    default void addDisconnectEvent(Instant timestamp) throws IOException {}

    default void addCloseEvent(Instant timestamp) throws IOException {}

    default void addDeregisterEvent(Instant timestamp) throws IOException {}

    default void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    default void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {}

    default void addFlushEvent(Instant timestamp) throws IOException {}

    default void addChannelRegisteredEvent(Instant timestamp) throws IOException {}

    default void addChannelUnregisteredEvent(Instant timestamp) throws IOException {}

    default void addChannelActiveEvent(Instant timestamp) throws IOException {}

    default void addChannelInactiveEvent(Instant timestamp) throws IOException {}

    default void addChannelReadEvent(Instant timestamp) throws IOException {}

    default void addChannelReadCompleteEvent(Instant timestamp) throws IOException {}

    default void addUserEventTriggeredEvent(Instant timestamp) throws IOException {}

    default void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException {}

    default void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {}

    default void addEndOfFirstLineIndicator(int characterIndex) throws IOException {}

    default void addEndOfHeadersIndicator(int characterIndex) throws IOException {}

    /**
     * Adds an end-of-record indicator and resets the index values in preparation of any new
     * messages that may come across the channel.
     *
     * @param timestamp
     * @throws IOException
     */
    default void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException {}

    default CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException {
        return CompletableFuture.completedFuture(null);
    }

    default void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException {}
}
