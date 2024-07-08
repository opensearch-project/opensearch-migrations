package com.rfs.tracing;

import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

public class BaseRootRfsContext extends RootOtelContext {
    public static final String SCOPE_NAME = "rfs";
    public final RfsContexts.GenericRequestContext.MetricInstruments genericRequestInstruments;
    public final RfsContexts.CheckedIdempotentPutRequestContext.MetricInstruments getTwoStepIdempotentRequestInstruments;

    public BaseRootRfsContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        genericRequestInstruments = RfsContexts.GenericRequestContext.makeMetrics(meter);
        getTwoStepIdempotentRequestInstruments = RfsContexts.CheckedIdempotentPutRequestContext.makeMetrics(meter);
    }
}
