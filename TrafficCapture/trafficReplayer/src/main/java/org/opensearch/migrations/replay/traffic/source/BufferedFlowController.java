package org.opensearch.migrations.replay.traffic.source;

import java.time.Duration;
import java.time.Instant;

public interface BufferedFlowController {
    void stopReadsPast(Instant pointInTime);

    Duration getBufferTimeWindow();
}
