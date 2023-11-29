package org.opensearch.migrations.tracing;

import java.time.Instant;

public interface IWithStartTime {
    Instant getStartTime();
}
