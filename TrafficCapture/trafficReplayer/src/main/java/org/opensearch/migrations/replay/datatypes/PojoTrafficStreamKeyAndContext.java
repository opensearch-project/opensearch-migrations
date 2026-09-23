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

*/
// REBUILD-LIMBO-END(G11)