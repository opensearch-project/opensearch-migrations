package org.opensearch.migrations.tracing;

import java.time.Instant;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

public class TestContext extends RootReplayerContext implements AutoCloseable {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;
    public final ChannelContextManager channelContextManager = new ChannelContextManager(this);
    private final Object channelContextManagerLock = new Object();

    public static TestContext withTracking(boolean tracing, boolean metrics) {
        return new TestContext(new InMemoryInstrumentationBundle(tracing, metrics), new BacktracingContextTracker());
    }

    public static TestContext withAllTracking() {
        return withTracking(true, true);
    }

    public static TestContext noOtelTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(null, null), new BacktracingContextTracker());
    }

    public TestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle, IContextTracker contextTracker) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public IReplayContexts.ITrafficStreamsLifecycleContext createTrafficStreamContextForTest(ITrafficStreamKey tsk) {
        synchronized (channelContextManagerLock) {
            return createTrafficStreamContextForStreamSource(channelContextManager.retainOrCreateContext(tsk), tsk);
        }
    }

    public BacktracingContextTracker getBacktracingContextTracker() {
        return (BacktracingContextTracker) getContextTracker();
    }

    @Override
    public void close() {
        // Assertions.assertEquals("", contextTracker.getAllRemainingActiveScopes().entrySet().stream()
        // .map(kvp->kvp.getKey().toString()).collect(Collectors.joining()));
        getBacktracingContextTracker().close();
        inMemoryInstrumentationBundle.close();
    }

    public final IReplayContexts.IReplayerHttpTransactionContext getTestConnectionRequestContext(int replayerIdx) {
        return getTestConnectionRequestContext(DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public IReplayContexts.IReplayerHttpTransactionContext getTestConnectionRequestContext(
        String connectionId,
        int replayerIdx
    ) {
        return getTestConnectionRequestContext(TEST_NODE_ID, connectionId, replayerIdx);
    }

    public IReplayContexts.IReplayerHttpTransactionContext getTestConnectionRequestContext(
        String nodeId,
        String connectionId,
        int replayerIdx
    ) {
        var rk = new UniqueReplayerRequestKey(
            PojoTrafficStreamKeyAndContext.build(
                nodeId,
                connectionId,
                0,
                this::createTrafficStreamContextForTest
            ),
            0,
            replayerIdx
        );
        return rk.trafficStreamKey.getTrafficStreamsContext().createHttpTransactionContext(rk, Instant.EPOCH);
    }

    public IReplayContexts.ITupleHandlingContext getTestTupleContext() {
        return getTestTupleContext(DEFAULT_TEST_CONNECTION, 1);
    }

    public IReplayContexts.ITupleHandlingContext getTestTupleContext(String connectionId, int replayerIdx) {
        return getTestConnectionRequestContext(connectionId, replayerIdx).createTupleContext();
    }
}
