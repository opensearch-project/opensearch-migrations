package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

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

    // ---- HTTP/2 capture API ----

    /**
     * Called once per connection after the TLS handshake completes and ALPN has selected a
     * protocol. Emit BEFORE any frame observation. The {@code negotiatedProtocol} value is the
     * ALPN string ("h2", "http/1.1") or empty string if no ALPN was negotiated.
     * {@code offeredByClient} is the raw comma-separated client advertisement, for forensic use.
     */
    default void addAlpnNegotiatedEvent(
            Instant timestamp,
            String negotiatedProtocol,
            String offeredByClient) throws IOException {}

    /**
     * Called once per H2 frame observed in the read direction (client → proxy).
     * The decoded payload is one of the {@link Http2FramePayload} variants.
     * {@code rawFrame} is the original 9-byte-header-plus-payload bytes; implementations
     * MUST take a defensive copy if they retain it past the call.
     *
     * <p>For HEADERS spanning HEADERS+CONTINUATION+...+CONTINUATION: callers SHOULD coalesce
     * into a single addH2FrameRead with payload = headers (with the merged header block) and
     * emit each CONTINUATION's rawFrame separately as its own observation for forensic
     * completeness.
     */
    default void addH2FrameRead(
            Instant timestamp,
            int streamId,
            Http2FrameType type,
            int flags,
            ByteBuf rawFrame,
            Http2FramePayload payload) throws IOException {}

    /**
     * Symmetric to {@link #addH2FrameRead}, for the write direction
     * (upstream → proxy → client).
     */
    default void addH2FrameWrite(
            Instant timestamp,
            int streamId,
            Http2FrameType type,
            int flags,
            ByteBuf rawFrame,
            Http2FramePayload payload) throws IOException {}
}
