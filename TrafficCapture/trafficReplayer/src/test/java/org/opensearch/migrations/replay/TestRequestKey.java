package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

public class TestRequestKey {
    public final static UniqueRequestKey getTestConnectionRequestId(int replayerIdx) {
        return new UniqueRequestKey(
                new PojoTrafficStreamKey("testNodeId", "testConnectionId", 0),
                0, replayerIdx);
    }
}
