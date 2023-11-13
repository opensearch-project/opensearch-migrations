package org.opensearch.migrations.replay.datatypes;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

@AllArgsConstructor
@Getter
public class PojoTrafficStreamWithKey implements ITrafficStreamWithKey {
    public final TrafficStream stream;
    public final ITrafficStreamKey key;
}