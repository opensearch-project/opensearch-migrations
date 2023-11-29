package org.opensearch.migrations.replay;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.RequestContext;

public class TestRequestKey {

    private TestRequestKey() {}

    public static final RequestContext getTestConnectionRequestContext(int replayerIdx) {
        var rk = new UniqueReplayerRequestKey(
                new PojoTrafficStreamKey("testNodeId", "testConnectionId", 0),
                0, replayerIdx);
        return new RequestContext(new UniqueReplayerRequestKey(rk.trafficStreamKey, 1, 1),
                GlobalOpenTelemetry.getTracer("test").spanBuilder("test").startSpan());
    }
}
