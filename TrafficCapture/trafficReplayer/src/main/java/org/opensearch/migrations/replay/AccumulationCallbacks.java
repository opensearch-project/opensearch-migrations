package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

import java.time.Instant;
import java.util.List;

public interface AccumulationCallbacks {
    void onRequestReceived(UniqueRequestKey key, HttpMessageAndTimestamp request);
    void onFullDataReceived(UniqueRequestKey key, RequestResponsePacketPair rrpp);
    void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status, List<ITrafficStreamKey> trafficStreamKeysBeingHeld);
    void onConnectionClose(UniqueRequestKey key, Instant when);
}
