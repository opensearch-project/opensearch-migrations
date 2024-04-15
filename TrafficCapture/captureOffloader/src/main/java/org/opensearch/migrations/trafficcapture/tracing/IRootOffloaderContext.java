package org.opensearch.migrations.trafficcapture.tracing;

import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootOffloaderContext extends IRootOtelContext {
    ConnectionContext.MetricInstruments getConnectionInstruments();
}
