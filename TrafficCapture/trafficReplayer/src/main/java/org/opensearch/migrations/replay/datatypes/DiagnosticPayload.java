package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;

import lombok.NonNull;

/**
 * Independently retained request packets used for evidence and diagnostics.
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
