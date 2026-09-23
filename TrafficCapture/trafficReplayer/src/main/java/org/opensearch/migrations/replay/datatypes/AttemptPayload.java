package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;

import lombok.NonNull;

/**
 * Attempt-scoped ownership of request packets.
 */
public final class AttemptPayload implements AutoCloseable {
    private final ByteBufList packets;
    private final ResourceOwnership.Tracker ownership;

    private AttemptPayload(
        @NonNull ByteBufList packets,
        @NonNull ResourceOwnership.Metrics metrics
    ) {
        this.packets = packets;
        this.ownership = new ResourceOwnership.Tracker(
            metrics,
            ResourceOwnership.Type.ATTEMPT_PAYLOAD,
            packets.size(),
            packets.readableBytes()
        );
    }

    public static AttemptPayload owned(@NonNull ByteBufList packets) {
        return owned(packets, ResourceOwnership.Metrics.NOOP);
    }

    public static AttemptPayload owned(
        @NonNull ByteBufList packets,
        @NonNull ResourceOwnership.Metrics metrics
    ) {
        return new AttemptPayload(packets, metrics);
    }

    public ByteBufList packets() {
        return packets;
    }

    @Override
    public void close() {
        ownership.close(packets::release);
    }
}
