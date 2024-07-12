package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface AccumulationCallbacks {
    Consumer<RequestResponsePacketPair> onRequestReceived(
        @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull HttpMessageAndTimestamp request
    );

    void onTrafficStreamsExpired(
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    );

    void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    );

    void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx);
}
