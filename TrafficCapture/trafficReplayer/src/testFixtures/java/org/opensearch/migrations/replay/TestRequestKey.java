package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.tracing.Contexts;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

public class TestRequestKey {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";
    private static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure("test");


    private TestRequestKey() {}

    public static final Contexts.HttpTransactionContext getTestConnectionRequestContext(int replayerIdx) {
        return getTestConnectionRequestContext(DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public static Contexts.HttpTransactionContext getTestConnectionRequestContext(String connectionId, int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                new PojoTrafficStreamKey(TEST_NODE_ID, connectionId, 0),
                0, replayerIdx);
        var tsCtx = new TestTrafficStreamsLifecycleContext(rk.trafficStreamKey);
        return new Contexts.HttpTransactionContext(tsCtx, rk, METERING_CLOSURE.makeSpanContinuation("test2"));
    }
}
