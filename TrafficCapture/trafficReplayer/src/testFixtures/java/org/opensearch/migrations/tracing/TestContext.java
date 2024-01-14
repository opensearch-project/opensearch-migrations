package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

public class TestContext extends RootReplayerContext {

    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;
    public final ChannelContextManager channelContextManager = new ChannelContextManager(this);

    public static TestContext withTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(InMemorySpanExporter.create(),
                InMemoryMetricExporter.create()));
    }

    public static TestContext noTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(null, null));
    }

    public TestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public IReplayContexts.ITrafficStreamsLifecycleContext createTrafficStreamContextForTest(ITrafficStreamKey tsk) {
        return createTrafficStreamContextForStreamSource(channelContextManager.retainOrCreateContext(tsk), tsk);
    }
}
