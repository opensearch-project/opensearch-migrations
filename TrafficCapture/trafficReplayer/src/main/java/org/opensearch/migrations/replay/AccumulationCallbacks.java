package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(@NonNull UniqueReplayerRequestKey key, @NonNull HttpMessageAndTimestamp request);
    void onFullDataReceived(@NonNull UniqueReplayerRequestKey key, @NonNull RequestResponsePacketPair rrpp);
    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                 @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onConnectionClose(@NonNull ISourceTrafficChannelKey key, int channelInteractionNumber, @NonNull Instant when,
                           @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
}
