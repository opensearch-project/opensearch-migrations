package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Assertions;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import java.util.stream.Collectors;

public class TestContext extends RootReplayerContext implements AutoCloseable {

    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;
    public final ContextTracker contextTracker = new ContextTracker();
    public final ChannelContextManager channelContextManager = new ChannelContextManager(this);

    public static TestContext withTracking(boolean tracing, boolean metrics) {
        return new TestContext(new InMemoryInstrumentationBundle(tracing, metrics));
    }

    public static TestContext withAllTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(InMemorySpanExporter.create(),
                InMemoryMetricExporter.create()));
    }

    public static TestContext noOtelTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(null, null));
    }

    @Override
    public void onContextCreated(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onCreated(newScopedContext);
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onClosed(newScopedContext);
    }

    public TestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public IReplayContexts.ITrafficStreamsLifecycleContext createTrafficStreamContextForTest(ITrafficStreamKey tsk) {
        return createTrafficStreamContextForStreamSource(channelContextManager.retainOrCreateContext(tsk), tsk);
    }

    @Override
    public void close() {
//        Assertions.assertEquals("", contextTracker.getAllRemainingActiveScopes().entrySet().stream()
//                .map(kvp->kvp.getKey().toString()).collect(Collectors.joining()));
    }
}
