package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import java.time.Duration;
import java.time.Instant;

public interface BufferedFlowController {
    void stopReadsPast(Instant pointInTime);
    Duration getBufferTimeWindow();
}
