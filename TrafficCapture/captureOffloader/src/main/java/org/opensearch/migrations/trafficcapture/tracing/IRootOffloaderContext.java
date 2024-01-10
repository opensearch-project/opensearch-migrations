package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootOffloaderContext extends IRootOtelContext {
    LongUpDownCounter getActiveConnectionsCounter();

}
