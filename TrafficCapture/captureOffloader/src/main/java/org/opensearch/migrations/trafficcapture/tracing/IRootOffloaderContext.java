package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootOffloaderContext extends IRootOtelContext {
    LongUpDownCounter getActiveConnectionsCounter();
}
