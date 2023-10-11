package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TrafficStreamKeyWithRequestOffset;

public class TestTrafficStreamKey {
    public final static TrafficStreamKeyWithRequestOffset instance =
            new TrafficStreamKeyWithRequestOffset(
                    new PojoTrafficStreamKey("testNodeId", "testConnectionId", 0), 0);
}
