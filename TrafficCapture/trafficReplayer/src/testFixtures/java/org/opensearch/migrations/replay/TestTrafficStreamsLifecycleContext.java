package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

class TestTrafficStreamsLifecycleContext extends ReplayContexts.TrafficStreamsLifecycleContext {
    private final ITrafficStreamKey trafficStreamKey;

    public TestTrafficStreamsLifecycleContext(RootReplayerContext rootContext, ITrafficStreamKey tsk) {
        super(rootContext, new ReplayContexts.ChannelKeyContext(rootContext, rootContext, tsk), tsk);
        this.trafficStreamKey = tsk;
        initializeSpan();
    }
}
