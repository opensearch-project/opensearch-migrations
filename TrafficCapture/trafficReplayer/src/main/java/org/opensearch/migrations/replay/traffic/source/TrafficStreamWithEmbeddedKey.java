package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public class TrafficStreamWithEmbeddedKey implements ITrafficStreamWithKey {
    public final TrafficStream stream;

    public TrafficStreamWithEmbeddedKey(TrafficStream stream) {
        this.stream = stream;
    }

    @Override
    public ITrafficStreamKey getKey() {
        return new PojoTrafficStreamKey(stream);
    }

    @Override
    public TrafficStream getStream() {
        return stream;
    }
}
