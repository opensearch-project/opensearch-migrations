package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

public class TrafficStreamWithEmbeddedKey implements ITrafficStreamWithKey {
    public final TrafficStream stream;

    public TrafficStreamWithEmbeddedKey(TrafficStream stream) {
        this.stream = stream;
    }

    @Override
    public TrafficStreamKey getKey() {
        return new TrafficStreamKey(stream.getNodeId(), stream.getConnectionId(),
                stream.hasNumber() ? stream.getNumber() : stream.getNumberOfThisLastChunk());
    }

    @Override
    public TrafficStream getStream() {
        return stream;
    }
}
