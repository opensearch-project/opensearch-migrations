package org.opensearch.migrations.replay;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import org.opensearch.migrations.coreutils.SimpleMeteringClosure;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelKeyContext;
import org.opensearch.migrations.replay.tracing.RequestContext;
import org.opensearch.migrations.tracing.EmptyContext;

public class TestRequestKey {

    private TestRequestKey() {}

    public static final RequestContext getTestConnectionRequestContext(int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                new PojoTrafficStreamKey("testNodeId", "testConnectionId", 0),
                0, replayerIdx);
        var smc = new SimpleMeteringClosure("test");
        var channelKeyContext = new ChannelKeyContext(rk.trafficStreamKey, smc.makeSpanContinuation("test", null));
        return new RequestContext(channelKeyContext, rk, smc.makeSpanContinuation("test2"));
    }
}
