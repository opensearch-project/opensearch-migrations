package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.Contexts;
import org.opensearch.migrations.replay.tracing.IContexts;
import org.opensearch.migrations.replay.tracing.IChannelKeyContext;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(@NonNull UniqueReplayerRequestKey key,
                           IContexts.IReplayerHttpTransactionContext ctx,
                           @NonNull HttpMessageAndTimestamp request);
    void onFullDataReceived(@NonNull UniqueReplayerRequestKey key,
                            IContexts.IReplayerHttpTransactionContext ctx,
                            @NonNull RequestResponsePacketPair rrpp);
    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                 IChannelKeyContext ctx,
                                 @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onConnectionClose(@NonNull ISourceTrafficChannelKey key, int channelInteractionNumber,
                           IChannelKeyContext ctx,
                           RequestResponsePacketPair.ReconstructionStatus status,
                           @NonNull Instant when,
                           @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk, IChannelKeyContext ctx);
}
