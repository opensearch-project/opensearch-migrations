package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

class TestTrafficStreamsLifecycleContext
        extends DirectNestedSpanContext<IReplayContexts.IChannelKeyContext>
        implements IReplayContexts.ITrafficStreamsLifecycleContext {

    private final ITrafficStreamKey trafficStreamKey;

    public TestTrafficStreamsLifecycleContext(IInstrumentationAttributes rootContext, ITrafficStreamKey tsk) {
        super(new ReplayContexts.ChannelKeyContext(rootContext, tsk));
        this.trafficStreamKey = tsk;
        initializeSpan();
    }

    public static final String SCOPE_NAME = "testScope";
    @Override
    public String getActivityName() { return "testTrafficSpan"; }

    @Override
    public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
        return getLogicalEnclosingScope();
    }

    @Override
    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKey;
    }

    @Override
    public void close() {
        super.close();
        getLogicalEnclosingScope().close();
    }
}
