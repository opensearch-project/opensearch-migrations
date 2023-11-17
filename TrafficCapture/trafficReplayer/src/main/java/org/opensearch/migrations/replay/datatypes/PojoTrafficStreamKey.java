package org.opensearch.migrations.replay.datatypes;

import lombok.ToString;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import java.util.StringJoiner;

public class PojoTrafficStreamKey implements ITrafficStreamKey {
    private final String nodeId;
    private final String connectionId;
    private final int trafficStreamIndex;

    public PojoTrafficStreamKey(TrafficStream stream) {
        this(stream.getNodeId(), stream.getConnectionId(), TrafficStreamUtils.getTrafficStreamIndex(stream));
    }

    public PojoTrafficStreamKey(String nodeId, String connectionId, int index) {
        this.nodeId = nodeId;
        this.connectionId = connectionId;
        this.trafficStreamIndex = index;
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public int getTrafficStreamIndex() {
        return trafficStreamIndex;
    }

    @Override
    public String toString() {
        return new StringJoiner(".")
                .add(nodeId)
                .add(connectionId)
                .add(""+trafficStreamIndex)
                .toString();
    }
}
