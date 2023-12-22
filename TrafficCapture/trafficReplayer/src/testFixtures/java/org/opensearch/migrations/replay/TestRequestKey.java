package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.tracing.Contexts;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
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
                PojoTrafficStreamKeyAndContext.build(TEST_NODE_ID, connectionId, 0,
                        tsk -> new TestTrafficStreamsLifecycleContext(tsk)),
                0, replayerIdx);
        return new Contexts.HttpTransactionContext(rk.trafficStreamKey.getTrafficStreamsContext(), rk);
    }
}
