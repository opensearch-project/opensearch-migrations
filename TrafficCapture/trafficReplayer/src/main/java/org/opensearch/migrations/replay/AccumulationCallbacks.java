package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(@NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                           @NonNull HttpMessageAndTimestamp request);

    void onFullDataReceived(@NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                            @NonNull RequestResponsePacketPair rrpp);

    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                 @NonNull IReplayContexts.IChannelKeyContext ctx,
                                 @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);

    void onConnectionClose(int channelInteractionNumber,
                           @NonNull IReplayContexts.IChannelKeyContext ctx,
                           RequestResponsePacketPair.ReconstructionStatus status,
                           @NonNull Instant when,
                           @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);

    void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx);
}
