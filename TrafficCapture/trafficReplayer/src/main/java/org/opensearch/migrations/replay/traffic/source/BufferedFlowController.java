package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;

public interface BufferedTimeController {
    void stopReadsPast(Instant pointInTime);
    Duration getBufferTimeWindow();
}
