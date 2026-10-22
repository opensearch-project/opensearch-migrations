package org.opensearch.migrations.trafficcapture.netty;

import java.time.Instant;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Connects the handler that writes client traffic to the source with the capture handler ahead of it in the
 * pipeline. The capture handler registers a listener on the client channel; the forwarding handler reports each
 * of its writes to the source that completed, in write order, with the time the write completed.
 */
public final class SourceForwardingCompletion {
    private static final AttributeKey<Consumer<Instant>> LISTENER =
        AttributeKey.valueOf(SourceForwardingCompletion.class, "listener");

    private SourceForwardingCompletion() {}

    static void setListener(Channel clientChannel, Consumer<Instant> listener) {
        clientChannel.attr(LISTENER).set(listener);
    }

    /**
     * Reports that the write of one forwarded message to the source completed at {@code timestamp}. The listener
     * runs on the client channel's event loop, which owns the connection's capture state.
     */
    public static void reportWriteCompleted(Channel clientChannel, Instant timestamp) {
        var listener = clientChannel.attr(LISTENER).get();
        if (listener == null) {
            return;
        }
        if (clientChannel.eventLoop().inEventLoop()) {
            listener.accept(timestamp);
        } else {
            clientChannel.eventLoop().execute(() -> listener.accept(timestamp));
        }
    }
}
