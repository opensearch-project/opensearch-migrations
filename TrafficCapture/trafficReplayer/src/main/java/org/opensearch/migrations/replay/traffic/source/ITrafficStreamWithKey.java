package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public interface ITrafficStreamWithKey {
    ITrafficStreamKey getKey();

    TrafficStream getStream();
}
