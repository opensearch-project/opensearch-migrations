package org.opensearch.migrations.replay.datatypes;

import lombok.EqualsAndHashCode;
import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;

@EqualsAndHashCode()
public abstract class PojoTrafficStreamKey implements ITrafficStreamKey {
    protected final String nodeId;
    protected final String connectionId;
    protected final int trafficStreamIndex;

    protected PojoTrafficStreamKey(String nodeId, String connectionId, int index) {
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
        return TrafficChannelKeyFormatter.format(nodeId, connectionId, trafficStreamIndex);
    }
}
