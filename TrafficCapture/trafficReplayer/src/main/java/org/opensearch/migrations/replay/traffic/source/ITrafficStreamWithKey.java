package org.opensearch.migrations.replay.traffic.source;

import java.time.Instant;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public interface ITrafficStreamWithKey {
    ITrafficStreamKey getKey();

    TrafficStream getStream();

    /**
     * Wall-clock instant before which the first request on this connection should not be sent.
     * Non-null only for handoff connections (no open observation, not in the active connection set)
     * where another replayer may have had in-flight requests. Null means no delay.
     */
    default Instant getQuiescentUntil() {
        return null;
    }
}
