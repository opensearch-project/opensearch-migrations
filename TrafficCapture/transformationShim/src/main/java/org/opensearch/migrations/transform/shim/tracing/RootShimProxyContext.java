package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;
import lombok.NonNull;

public class RootShimProxyContext extends RootOtelContext {
    public static final String SCOPE_NAME = "shimProxy";

    public final ShimRequestContext.MetricInstruments shimRequestInstruments;
    public final TargetDispatchContext.MetricInstruments targetDispatchInstruments;
    public final TransformContext.MetricInstruments transformInstruments;

    public RootShimProxyContext(@NonNull OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);
        shimRequestInstruments = ShimRequestContext.makeMetrics(meter);
        targetDispatchInstruments = TargetDispatchContext.makeMetrics(meter);
        transformInstruments = TransformContext.makeMetrics(meter);
    }
}
