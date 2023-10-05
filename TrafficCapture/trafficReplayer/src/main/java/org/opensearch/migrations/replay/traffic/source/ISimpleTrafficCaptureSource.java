package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;

public interface ISimpleTrafficCaptureSource extends ITrafficCaptureSource {
    void commitTrafficStream(TrafficStreamKey trafficStreamKey);
}
