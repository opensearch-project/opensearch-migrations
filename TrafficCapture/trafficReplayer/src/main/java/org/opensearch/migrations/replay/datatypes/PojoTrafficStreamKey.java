package org.opensearch.migrations.replay.datatypes;

public class PojoTrafficStreamKey implements ITrafficStreamKey {
    private final String nodeId;
    private final String connectionId;
    private final int trafficStreamIndex;

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
}
