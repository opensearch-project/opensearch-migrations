package org.opensearch.migrations.replay.datatypes;

public class TrafficStreamKey {
    public final String nodeId;
    public final String connectionId;
    public final int trafficStreamIndex;

    public TrafficStreamKey(String nodeId, String connectionId, int index) {
        this.nodeId = nodeId;
        this.connectionId = connectionId;
        this.trafficStreamIndex = index;
    }
}
