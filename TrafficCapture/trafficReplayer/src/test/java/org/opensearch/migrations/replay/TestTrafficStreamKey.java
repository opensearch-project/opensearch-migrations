package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;

public class TestTrafficStreamKey {
    public final static ITrafficStreamKey instance =
            new PojoTrafficStreamKey("testNodeId", "testConnectionId", 0);
}
