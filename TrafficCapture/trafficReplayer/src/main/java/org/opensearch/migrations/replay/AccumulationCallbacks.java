package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TerminalSourceConnectionId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface AccumulationCallbacks {
    /**
     * @param isResumedConnection true when this is the first request on a connection that was
     *                            mid-flight during a Kafka partition reassignment.
     */
    Consumer<RequestResponsePacketPair> onRequestReceived(
        @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull HttpMessageAndTimestamp request,
        boolean isResumedConnection
    );

    default void onTrafficStreamsExpired(
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        @NonNull ITrafficStreamKey connectionKey
    ) {
        onTrafficStreamsExpired(status, ctx, List.of(connectionKey));
    }

    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
    @Deprecated
    default void onTrafficStreamsExpired(
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    ) {}

    default void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull ITrafficStreamKey connectionKey,
        @NonNull Optional<TerminalSourceConnectionId> terminalAssociation
    ) {
        onConnectionClose(
            channelInteractionNum,
            ctx,
            channelSessionNumber,
            status,
            timestamp,
            List.of(connectionKey)
        );
    }

    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
    @Deprecated
    default void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    ) {}

    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
    @Deprecated
    default void onTrafficStreamIgnored(
        @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
    ) {}
}
