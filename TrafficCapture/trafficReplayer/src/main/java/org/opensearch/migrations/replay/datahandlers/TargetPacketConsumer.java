package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
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

        record NoTargetResponseObtained(@NonNull IOException cause) implements PacketSendOutcome {
            public NoTargetResponseDiagnostic diagnostic() {
                return NoTargetResponseDiagnostic.fromCause(
                    NoTargetResponseKind.TRANSPORT_FAILURE,
                    cause
                );
            }

            public String reason() {
                return diagnostic().description();
            }

            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onNoTargetResponseObtained(this);
            }
        }
    }

    TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet);
}
