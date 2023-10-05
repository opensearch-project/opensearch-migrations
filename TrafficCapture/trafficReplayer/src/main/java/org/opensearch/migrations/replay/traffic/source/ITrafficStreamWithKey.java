package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public interface ITrafficStreamWithKey {
    TrafficStreamKey getKey();

    TrafficStream getStream();
}
