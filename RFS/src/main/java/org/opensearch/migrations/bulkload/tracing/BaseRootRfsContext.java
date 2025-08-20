package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;

public class BaseRootRfsContext extends RootOtelContext {
    public final RfsContexts.GenericRequestContext.MetricInstruments genericRequestInstruments;
    public final RfsContexts.CheckedIdempotentPutRequestContext.MetricInstruments getTwoStepIdempotentRequestInstruments;
    public final RfsContexts.DeltaStreamContext.MetricInstruments deltaStreamInstruments;

    public BaseRootRfsContext(String scopeName, OpenTelemetry sdk, IContextTracker contextTracker) {
        super(scopeName, contextTracker, sdk);
        var meter = this.getMeterProvider().get(scopeName);

        genericRequestInstruments = RfsContexts.GenericRequestContext.makeMetrics(meter);
        getTwoStepIdempotentRequestInstruments = RfsContexts.CheckedIdempotentPutRequestContext.makeMetrics(meter);
        deltaStreamInstruments = RfsContexts.DeltaStreamContext.makeMetrics(meter);
    }
}
