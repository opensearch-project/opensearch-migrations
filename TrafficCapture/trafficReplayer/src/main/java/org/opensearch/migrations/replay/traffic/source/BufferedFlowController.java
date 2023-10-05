package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;

import java.time.Duration;
import java.time.Instant;

public interface BufferedFlowController {
    void stopReadsPast(Instant pointInTime);
    Duration getBufferTimeWindow();

    void doneProcessing(TrafficStreamKey key);
}
