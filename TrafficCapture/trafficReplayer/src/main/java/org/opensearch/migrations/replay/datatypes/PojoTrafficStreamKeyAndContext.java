package org.opensearch.migrations.replay.datatypes;

import java.util.function.Function;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

@EqualsAndHashCode(callSuper = true)
public class PojoTrafficStreamKeyAndContext extends PojoTrafficStreamKey {
    @Getter
    @Setter
    @NonNull
    IReplayContexts.ITrafficStreamsLifecycleContext trafficStreamsContext;

    public static PojoTrafficStreamKeyAndContext
    build(TrafficStream stream,
          Function<ITrafficStreamKey, IReplayContexts.ITrafficStreamsLifecycleContext> contextSupplier) {
        var rval = new PojoTrafficStreamKeyAndContext(stream.getNodeId(), stream.getConnectionId(),
                TrafficStreamUtils.getTrafficStreamIndex(stream));
        rval.setTrafficStreamsContext(contextSupplier.apply(rval));
        return rval;
    }

    protected PojoTrafficStreamKeyAndContext(TrafficStream stream) {
        this(stream.getNodeId(), stream.getConnectionId(), TrafficStreamUtils.getTrafficStreamIndex(stream));
    }

    public static PojoTrafficStreamKeyAndContext build(String nodeId, String connectionId, int index, Function<ITrafficStreamKey,
            IReplayContexts.ITrafficStreamsLifecycleContext> contextSupplier) {
        var rval = new PojoTrafficStreamKeyAndContext(nodeId, connectionId, index);
        rval.setTrafficStreamsContext(contextSupplier.apply(rval));
        return rval;
    }

    protected PojoTrafficStreamKeyAndContext(String nodeId, String connectionId, int index) {
        super(nodeId, connectionId, index);
    }

}
