package org.opensearch.migrations.replay.datatypes;

import java.util.function.Function;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Setter;

@EqualsAndHashCode(callSuper = true)
public class PojoTrafficStreamKeyAndContext extends PojoTrafficStreamKey {
    @Setter
    IReplayContexts.ITrafficStreamsLifecycleContext trafficStreamsContext;

    public static PojoTrafficStreamKeyAndContext build(
        TrafficStream stream,
        Function<ITrafficStreamKey, IReplayContexts.ITrafficStreamsLifecycleContext> contextSupplier
    ) {
        var rval = new PojoTrafficStreamKeyAndContext(
            stream.getNodeId(),
            stream.getConnectionId(),
            TrafficStreamUtils.getTrafficStreamIndex(stream)
        );
        rval.setTrafficStreamsContext(contextSupplier.apply(rval));
        return rval;
    }

    public static PojoTrafficStreamKeyAndContext build(
        ISourceTrafficChannelKey sourceKey,
        int index,
        Function<ITrafficStreamKey, IReplayContexts.ITrafficStreamsLifecycleContext> contextSupplier
    ) {
        return build(sourceKey.getNodeId(), sourceKey.getConnectionId(), index, contextSupplier);
    }

    public static PojoTrafficStreamKeyAndContext build(
        String nodeId,
        String connectionId,
        int index,
        Function<ITrafficStreamKey, IReplayContexts.ITrafficStreamsLifecycleContext> contextSupplier
    ) {
        var rval = new PojoTrafficStreamKeyAndContext(nodeId, connectionId, index);
        rval.setTrafficStreamsContext(contextSupplier.apply(rval));
        return rval;
    }

    protected PojoTrafficStreamKeyAndContext(TrafficStream stream) {
        this(stream.getNodeId(), stream.getConnectionId(), TrafficStreamUtils.getTrafficStreamIndex(stream));
    }

    private PojoTrafficStreamKeyAndContext(String nodeId, String connectionId, int index) {
        super(nodeId, connectionId, index);
    }

    @NonNull
    public IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext() {
        return trafficStreamsContext;
    }
}
