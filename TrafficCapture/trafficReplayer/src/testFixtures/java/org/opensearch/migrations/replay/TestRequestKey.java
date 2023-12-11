package org.opensearch.migrations.replay;

import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelKeyContext;
import org.opensearch.migrations.replay.tracing.RequestContext;

public class TestRequestKey {

    public static final String TEST_NODE_ID = "testNodeId";
    public static final String DEFAULT_TEST_CONNECTION = "testConnection";

    private TestRequestKey() {}

    public static final RequestContext getTestConnectionRequestContext(int replayerIdx) {
        return getTestConnectionRequestContext(DEFAULT_TEST_CONNECTION, replayerIdx);
    }

    public static final RequestContext getTestConnectionRequestContext(String connectionId, int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                new PojoTrafficStreamKey(TEST_NODE_ID, connectionId, 0),
                0, replayerIdx);
        var smc = new SimpleMeteringClosure("test");
        var channelKeyContext = new ChannelKeyContext(rk.trafficStreamKey, smc.makeSpanContinuation("test", null));
        return new RequestContext(channelKeyContext, rk, smc.makeSpanContinuation("test2"));
    }
}
