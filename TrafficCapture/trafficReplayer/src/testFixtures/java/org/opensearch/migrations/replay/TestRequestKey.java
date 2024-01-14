package org.opensearch.migrations.replay;

import java.time.Instant;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.TestContext;

public class TestRequestKey {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";

    private TestRequestKey() {}

    public static final IReplayContexts.IReplayerHttpTransactionContext
    getTestConnectionRequestContext(TestContext ctx, int replayerIdx) {
        return getTestConnectionRequestContext(ctx, DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public static IReplayContexts.IReplayerHttpTransactionContext
    getTestConnectionRequestContext(TestContext ctx, String connectionId, int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                PojoTrafficStreamKeyAndContext.build(TEST_NODE_ID, connectionId, 0,
                        ctx::createTrafficStreamContextForTest),
                0, replayerIdx);
        return rk.trafficStreamKey.getTrafficStreamsContext().createHttpTransactionContext(rk, Instant.EPOCH);
    }
}
