package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TerminalSourceConnectionId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface AccumulationCallbacks {
*/
// REBUILD-LIMBO-END(G11)
    /**
     * @param isResumedConnection true when this is the first request on a connection that was
     *                            mid-flight during a Kafka partition reassignment.
     */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
// REBUILD-LIMBO-START(G11)
/*
    @Deprecated
    default void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    ) {}

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Temporary source-test adapter while the remaining owner milestones replace legacy callbacks.
     */
// REBUILD-LIMBO-START(G11)
/*
    @Deprecated
    default void onTrafficStreamIgnored(
        @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
    ) {}
}

*/
// REBUILD-LIMBO-END(G11)