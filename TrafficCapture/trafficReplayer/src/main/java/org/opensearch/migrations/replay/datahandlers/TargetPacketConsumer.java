package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;

/**
 * Defines the asynchronous boundary where encoded request packets are handed to a target connection.
 *
 * <p>Submitting bytes and obtaining an HTTP response are different outcomes: a packet may be accepted for
 * transport even though the connection later closes without producing a response. The sealed
 * {@link PacketSendOutcome} hierarchy represents both expected results as values, leaving exceptional
 * completion for broken invariants and unexpected implementation failures.</p>
 *
 * <p>The visitor makes handling exhaustive as new outcome types are introduced. That keeps retry,
 * connection, and request-lifecycle state machines from accidentally treating a normal no-response case
 * as an unclassified asynchronous exception.</p>
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

        record NoTargetResponseObtained(
            @NonNull NoTargetResponseDiagnostic diagnostic
        ) implements PacketSendOutcome {
            public String reason() {
                return diagnostic.description();
            }

            @Override
            public <R> R visit(Visitor<R> visitor) {
                return visitor.onNoTargetResponseObtained(this);
            }
        }
    }

    TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet);
}
