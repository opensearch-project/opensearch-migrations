package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootOffloaderContext extends IInstrumentConstructor {
    LongUpDownCounter getActiveConnectionsCounter();

}
