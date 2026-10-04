package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;

import lombok.NonNull;

/**
 * Owns a retained copy of request packets whose lifetime is controlled by diagnostic processing.
 *
 * <p>Failure evidence may need the request bytes after the target attempt and its prepared request have
 * completed. This wrapper separates that lifetime from replay execution: the packets remain readable until
 * the diagnostic payload is closed, and access after close is rejected rather than exposing released
 * Netty buffers.</p>
 *
 * <p>Closing the payload releases its retained buffers exactly once and records the corresponding resource
 * transition. Diagnostics can therefore outlive normal request cleanup without leaking memory or borrowing
 * ownership implicitly.</p>
 */
public final class DiagnosticPayload implements AutoCloseable {
    private final ByteBufList packets;
    private final ResourceOwnership.Tracker ownership;

    public DiagnosticPayload(@NonNull ByteBufList packets) {
        this(packets, ResourceOwnership.Metrics.NOOP);
    }

    public DiagnosticPayload(
        @NonNull ByteBufList packets,
        @NonNull ResourceOwnership.Metrics metrics
    ) {
        this.packets = packets;
        this.ownership = new ResourceOwnership.Tracker(
            metrics,
            ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD,
            packets.size(),
            packets.readableBytes()
        );
    }

    public ByteBufList packets() {
        if (packets.isClosed()) {
            throw new IllegalStateException("diagnostic payload is closed");
        }
        return packets;
    }

    public boolean isClosed() {
        return packets.isClosed();
    }

    @Override
    public void close() {
        ownership.close(packets::release);
    }
}
