package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(UniqueReplayerRequestKey key, HttpMessageAndTimestamp request);
    void onFullDataReceived(UniqueReplayerRequestKey key, RequestResponsePacketPair rrpp);
    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                 List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onConnectionClose(UniqueReplayerRequestKey key, Instant when,
                           List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
}
