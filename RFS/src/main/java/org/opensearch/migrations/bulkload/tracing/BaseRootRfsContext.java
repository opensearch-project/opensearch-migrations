package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

public class BaseRootRfsContext extends RootOtelContext {
    public final RfsContexts.GenericRequestContext.MetricInstruments genericRequestInstruments;
    public final RfsContexts.CheckedIdempotentPutRequestContext.MetricInstruments getTwoStepIdempotentRequestInstruments;
    public final RfsContexts.DeltaStreamContext.MetricInstruments deltaStreamInstruments;
    private final String scopeName;

    public BaseRootRfsContext(String scopeName, OpenTelemetry sdk, IContextTracker contextTracker) {
        super(scopeName, contextTracker, sdk);
        this.scopeName = scopeName;
        var meter = this.getMeterProvider().get(scopeName);

        genericRequestInstruments = RfsContexts.GenericRequestContext.makeMetrics(meter);
        getTwoStepIdempotentRequestInstruments = RfsContexts.CheckedIdempotentPutRequestContext.makeMetrics(meter);
        deltaStreamInstruments = RfsContexts.DeltaStreamContext.makeMetrics(meter);
    }

    /**
     * Instruments for one target's bulk flow control, with gauges reading {@code budget} and {@code limit} (either
     * may be null). Create once per process and target.
     */
    public static final String WORKER_PHASE = "rfsWorkerPhase";
    public static final AttributeKey<String> PHASE = AttributeKey.stringKey("phase");

    /**
     * A gauge of this process's {@link WorkerPhase}: one series per phase, 1 for the current phase and 0 for the
     * others, reported on every export, so a dashboard can count workers in each phase at any moment. Keep the
     * returned handle for as long as the gauge should report.
     */
    public AutoCloseable makeWorkerPhaseGauge() {
        var phases = java.util.Arrays.stream(WorkerPhase.Phase.values())
            .map(p -> java.util.Map.entry(p, Attributes.of(PHASE, p.label()))).toList();
        return getMeterProvider().get(scopeName).gaugeBuilder(WORKER_PHASE).setUnit("count").ofLongs()
            .buildWithCallback(m -> {
                var current = WorkerPhase.current();
                phases.forEach(e -> m.record(e.getKey() == current ? 1 : 0, e.getValue()));
            });
    }

    public BulkFlowInstruments makeBulkFlowInstruments(RetryBudget budget, AdaptiveLimit limit) {
        return new BulkFlowInstruments(getMeterProvider().get(scopeName), budget, limit);
    }
}
