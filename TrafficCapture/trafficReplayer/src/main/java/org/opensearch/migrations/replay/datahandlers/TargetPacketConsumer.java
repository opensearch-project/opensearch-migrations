package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;

/**
 * Target-only packet boundary whose expected transport outcomes are explicit values.
 */
public interface TargetPacketConsumer extends IPacketFinalizingConsumer<AggregatedRawResponse> {
    sealed interface PacketSendOutcome
        permits PacketSendOutcome.PacketSubmitted,
            PacketSendOutcome.NoTargetResponseObtained {

        <R> R visit(Visitor<R> visitor);

        interface Visitor<R> {
            R onPacketSubmitted(PacketSubmitted outcome);

            R onNoTargetResponseObtained(NoTargetResponseObtained outcome);
        }

        record PacketSubmitted() implements PacketSendOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onPacketSubmitted(this);
            }
        }

        record NoTargetResponseObtained(@NonNull String reason) implements PacketSendOutcome {
            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onNoTargetResponseObtained(this);
            }
        }
    }

    TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet);
}
