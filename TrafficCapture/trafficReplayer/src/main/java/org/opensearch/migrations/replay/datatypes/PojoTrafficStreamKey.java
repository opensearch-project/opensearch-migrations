package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
public abstract class PojoTrafficStreamKey extends ISourceTrafficChannelKey.PojoImpl implements ITrafficStreamKey {
    protected final int trafficStreamIndex;

    protected PojoTrafficStreamKey(String nodeId, String connectionId, int index) {
        super(nodeId, connectionId);
        this.trafficStreamIndex = index;
    }

    protected PojoTrafficStreamKey(PojoImpl tsk, int index) {
        this(tsk.nodeId, tsk.connectionId, index);
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
