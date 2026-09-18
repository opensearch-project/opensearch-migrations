package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public interface ITrafficStreamWithKey extends SourceInput {
    ITrafficStreamKey getKey();

    TrafficStream getStream();

    /**
     * True when this is the first stream for a connection that was mid-flight during a Kafka
     * partition reassignment — another replayer may have had in-flight requests on this connection.
     * The replay engine should apply a quiescent delay before sending the first request.
     */
    default boolean isResumedConnection() {
        return false;
    }
}
