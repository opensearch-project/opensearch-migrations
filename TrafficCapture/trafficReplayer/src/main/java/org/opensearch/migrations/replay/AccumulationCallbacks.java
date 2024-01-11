package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(@NonNull UniqueReplayerRequestKey key,
                           IReplayContexts.IReplayerHttpTransactionContext ctx,
                           @NonNull HttpMessageAndTimestamp request);
    void onFullDataReceived(@NonNull UniqueReplayerRequestKey key,
                            IReplayContexts.IReplayerHttpTransactionContext ctx,
                            @NonNull RequestResponsePacketPair rrpp);
    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                 IReplayContexts.IChannelKeyContext ctx,
                                 @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onConnectionClose(@NonNull ISourceTrafficChannelKey key, int channelInteractionNumber,
                           IReplayContexts.IChannelKeyContext ctx,
                           RequestResponsePacketPair.ReconstructionStatus status,
                           @NonNull Instant when,
                           @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk,
                                IReplayContexts.IChannelKeyContext ctx);
}
