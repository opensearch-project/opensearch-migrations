package com.rfs.tracing;

import io.opentelemetry.api.OpenTelemetry;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

public class BaseRootRfsContext extends RootOtelContext {
    public final RfsContexts.GenericRequestContext.MetricInstruments genericRequestInstruments;
    public final RfsContexts.CheckedIdempotentPutRequestContext.MetricInstruments getTwoStepIdempotentRequestInstruments;

    public BaseRootRfsContext(String scopeName, OpenTelemetry sdk, IContextTracker contextTracker) {
        super(scopeName, contextTracker, sdk);
        var meter = this.getMeterProvider().get(scopeName);

        genericRequestInstruments = RfsContexts.GenericRequestContext.makeMetrics(meter);
        getTwoStepIdempotentRequestInstruments = RfsContexts.CheckedIdempotentPutRequestContext.makeMetrics(meter);
    }
}
