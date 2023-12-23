package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

public class TestRequestKey {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";

    private TestRequestKey() {}

    public static final ReplayContexts.HttpTransactionContext
    getTestConnectionRequestContext(IInstrumentationAttributes ctx, int replayerIdx) {
        return getTestConnectionRequestContext(ctx, DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public static ReplayContexts.HttpTransactionContext
    getTestConnectionRequestContext(IInstrumentationAttributes ctx, String connectionId, int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                PojoTrafficStreamKeyAndContext.build(TEST_NODE_ID, connectionId, 0,
                        tsk -> new TestTrafficStreamsLifecycleContext(ctx, tsk)),
                0, replayerIdx);
        return new ReplayContexts.HttpTransactionContext(rk.trafficStreamKey.getTrafficStreamsContext(), rk);
    }
}
