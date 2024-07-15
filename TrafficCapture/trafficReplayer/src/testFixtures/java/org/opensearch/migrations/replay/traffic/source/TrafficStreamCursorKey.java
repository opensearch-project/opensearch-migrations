package org.opensearch.migrations.replay.traffic.source;

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
    @Getter
    public final IReplayContexts.ITrafficStreamsLifecycleContext trafficStreamsContext;

    public TrafficStreamCursorKey(TestContext context, TrafficStream stream, int arrayIndex) {
        connectionId = stream.getConnectionId();
        nodeId = stream.getNodeId();
        trafficStreamIndex = TrafficStreamUtils.getTrafficStreamIndex(stream);
        this.arrayIndex = arrayIndex;
        trafficStreamsContext = context.createTrafficStreamContextForTest(this);
    }

    @Override
    public int compareTo(TrafficStreamCursorKey other) {
        return Integer.compare(arrayIndex, other.arrayIndex);
    }
}
