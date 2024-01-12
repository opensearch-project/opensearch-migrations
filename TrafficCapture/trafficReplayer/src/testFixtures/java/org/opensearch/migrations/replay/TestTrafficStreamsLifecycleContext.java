package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.time.Instant;

class TestTrafficStreamsLifecycleContext extends ReplayContexts.TrafficStreamsLifecycleContext {
    private final ITrafficStreamKey trafficStreamKey;

    public TestTrafficStreamsLifecycleContext(RootReplayerContext rootContext, ITrafficStreamKey tsk) {
        super(new ReplayContexts.ChannelKeyContext(rootContext, rootContext, tsk), tsk, rootContext);
        this.trafficStreamKey = tsk;
        initializeSpan();
    }
}
