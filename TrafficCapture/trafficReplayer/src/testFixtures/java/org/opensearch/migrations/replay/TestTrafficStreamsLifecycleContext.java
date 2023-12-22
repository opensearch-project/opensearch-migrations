package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.RootOtelContext;

class TestTrafficStreamsLifecycleContext
        extends DirectNestedSpanContext<IReplayContexts.IChannelKeyContext>
        implements IReplayContexts.ITrafficStreamsLifecycleContext {

    private final ITrafficStreamKey trafficStreamKey;

    public TestTrafficStreamsLifecycleContext(ITrafficStreamKey tsk) {
        super(new ReplayContexts.ChannelKeyContext(new RootOtelContext(), tsk));
        this.trafficStreamKey = tsk;
        setCurrentSpan("testSpan");
    }

    @Override
    public String getScopeName() { return "testScope"; }

    @Override
    public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
        return getLogicalEnclosingScope();
    }

    @Override
    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKey;
    }
}
