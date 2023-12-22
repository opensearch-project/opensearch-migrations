package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ChannelKeyContext;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IChannelKeyContext;
import org.opensearch.migrations.replay.tracing.IContexts;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;

class TestTrafficStreamsLifecycleContext
        extends DirectNestedSpanContext<IChannelKeyContext>
        implements IContexts.ITrafficStreamsLifecycleContext {
    private static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure("test");

    private final ITrafficStreamKey trafficStreamKey;

    public TestTrafficStreamsLifecycleContext(ITrafficStreamKey tsk) {
        super(new ChannelKeyContext(new RootOtelContext(), tsk));
        this.trafficStreamKey = tsk;
        setCurrentSpan("testScope","testSpan");
    }

    @Override
    public IChannelKeyContext getChannelKeyContext() {
        return getLogicalEnclosingScope();
    }

    @Override
    public ITrafficStreamKey getTrafficStreamKey() {
        return trafficStreamKey;
    }
}
