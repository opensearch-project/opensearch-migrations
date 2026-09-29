package org.opensearch.migrations.tracing;

import java.time.Instant;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import org.apache.kafka.common.TopicPartition;

// REBUILD-LIMBO(G10) -- the legacy traffic-key-backed predecessor remains inert while the current-identity
// fixture below proves the live transformation callers. Resolve the retained predecessor before G10 exits.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

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

    public IReplayContexts.IChannelKeyContext releaseChannelContextForTest(
        IReplayContexts.IChannelKeyContext context
    ) {
        synchronized (channelContextManagerLock) {
            return channelContextManager.releaseContextFor(context);
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
        var rk = new UniqueReplayerRequestKey(
            PojoTrafficStreamKeyAndContext.build(
                TEST_NODE_ID,
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

*/
// REBUILD-LIMBO-END(G10)

public class TestContext extends RootReplayerContext implements AutoCloseable {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";
    private static final TopicPartition TEST_TOPIC_PARTITION = new TopicPartition("test-fixture", 0);

    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;
    private long nextSyntheticRecordOffset;

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

    public BacktracingContextTracker getBacktracingContextTracker() {
        return (BacktracingContextTracker) getContextTracker();
    }

    @Override
    public void close() {
        getBacktracingContextTracker().close();
        inMemoryInstrumentationBundle.close();
    }

    public final IReplayContexts.IRequestContext getTestConnectionRequestContext(int replayerIdx) {
        return getTestConnectionRequestContext(DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public IReplayContexts.IRequestContext getTestConnectionRequestContext(
        String connectionId,
        int replayerIdx
    ) {
        var generation = new PartitionGenerationId(TEST_TOPIC_PARTITION, 0);
        var connection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId(TEST_NODE_ID, connectionId),
            0
        );
        var requestId = new ReplayRequestId(connection, replayerIdx);
        var recordContext = createKafkaRecordContext(
            new KafkaRecordId(generation, nextSyntheticRecordOffset++),
            0
        );
        return recordContext.createTrafficStreamContext(0).createRequestContext(requestId, Instant.EPOCH);
    }

    public IReplayContexts.ITupleHandlingContext getTestTupleContext() {
        return getTestTupleContext(DEFAULT_TEST_CONNECTION, 1);
    }

    public IReplayContexts.ITupleHandlingContext getTestTupleContext(String connectionId, int replayerIdx) {
        return getTestConnectionRequestContext(connectionId, replayerIdx).createTupleContext();
    }
}
