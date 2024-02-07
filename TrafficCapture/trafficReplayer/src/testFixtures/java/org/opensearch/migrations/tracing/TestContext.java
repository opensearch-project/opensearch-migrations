package org.opensearch.migrations.tracing;

import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import java.time.Instant;

public class TestContext extends RootReplayerContext implements AutoCloseable {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;
    public final ContextTracker contextTracker = new ContextTracker();
    public final ChannelContextManager channelContextManager = new ChannelContextManager(this);
    private final Object channelContextManagerLock = new Object();

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

    public TestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    @Override
    public void onContextCreated(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onCreated(newScopedContext);
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onClosed(newScopedContext);
    }

    public IReplayContexts.ITrafficStreamsLifecycleContext createTrafficStreamContextForTest(ITrafficStreamKey tsk) {
        synchronized (channelContextManagerLock) {
            return createTrafficStreamContextForStreamSource(channelContextManager.retainOrCreateContext(tsk), tsk);
        }
    }

    @Override
    public void close() {
//        Assertions.assertEquals("", contextTracker.getAllRemainingActiveScopes().entrySet().stream()
//                .map(kvp->kvp.getKey().toString()).collect(Collectors.joining()));
    }


    public final IReplayContexts.IReplayerHttpTransactionContext
    getTestConnectionRequestContext(int replayerIdx) {
        return getTestConnectionRequestContext(DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public IReplayContexts.IReplayerHttpTransactionContext
    getTestConnectionRequestContext(String connectionId, int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                PojoTrafficStreamKeyAndContext.build(TEST_NODE_ID, connectionId, 0,
                        this::createTrafficStreamContextForTest),
                0, replayerIdx);
        return rk.trafficStreamKey.getTrafficStreamsContext().createHttpTransactionContext(rk, Instant.EPOCH);
    }

    public IReplayContexts.ITupleHandlingContext
    getTestTupleContext() {
        return getTestTupleContext(DEFAULT_TEST_CONNECTION, 1);
    }

    public IReplayContexts.ITupleHandlingContext
    getTestTupleContext(String connectionId, int replayerIdx) {
        return getTestConnectionRequestContext(connectionId, replayerIdx).createTupleContext();
    }
}
