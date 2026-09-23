package org.opensearch.migrations.replay.datatypes;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)