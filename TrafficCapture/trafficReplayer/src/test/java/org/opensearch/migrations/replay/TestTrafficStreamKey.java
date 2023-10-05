package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;

public class TestTrafficStreamKey {
    public final static TrafficStreamKey instance =
            new TrafficStreamKey("testNodeId", "testConnectionId", 0);
}
