package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: IReplayContexts ITrafficStreamKey . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@EqualsAndHashCode
public class TrafficStreamCursorKey implements ITrafficStreamKey, Comparable<TrafficStreamCursorKey> {
    public final int arrayIndex;

    public final String connectionId;
    public final String nodeId;
    public final int trafficStreamIndex;
    public final int sourceGeneration;
    @Getter
    public final IReplayContexts.ITrafficStreamsLifecycleContext trafficStreamsContext;

    public TrafficStreamCursorKey(
        TestContext context,
        TrafficStream stream,
        int arrayIndex,
        int sourceGeneration
    ) {
        connectionId = stream.getConnectionId();
        nodeId = stream.getNodeId();
        trafficStreamIndex = TrafficStreamUtils.getTrafficStreamIndex(stream);
        this.arrayIndex = arrayIndex;
        this.sourceGeneration = sourceGeneration;
        trafficStreamsContext = context.createTrafficStreamContextForTest(this);
    }

    @Override
    public int getSourceGeneration() {
        return sourceGeneration;
    }

    @Override
    public int compareTo(TrafficStreamCursorKey other) {
        return Integer.compare(arrayIndex, other.arrayIndex);
    }
}

*/
// REBUILD-LIMBO-END(G10)