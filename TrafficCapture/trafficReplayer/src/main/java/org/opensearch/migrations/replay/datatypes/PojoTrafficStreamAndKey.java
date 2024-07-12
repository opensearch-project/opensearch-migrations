package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class PojoTrafficStreamAndKey implements ITrafficStreamWithKey {
    public final TrafficStream stream;
    public final ITrafficStreamKey key;
}
