package org.opensearch.migrations.bulkload.tracing;

import java.util.function.IntConsumer;

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

    public static final String WORKER_PHASE = "rfsWorkerPhase";
    public static final String WORK_ITEMS_ESCALATED = "workItemsEscalated";
    public static final AttributeKey<String> PHASE = AttributeKey.stringKey("phase");
    public static final AttributeKey<String> MEMORY_TIER = AttributeKey.stringKey("tier");

    /**
     * A gauge of this process's {@link WorkerPhase}: one series per phase, 1 for the current phase and 0 for the
     * others, reported on every export, so a dashboard can count workers in each phase at any moment. The worker's
     * memory tier is a dimension so dashboards can show fleet composition per tier (0 when memory scaling is off).
     * Keep the returned handle for as long as the gauge should report.
     */
    public AutoCloseable makeWorkerPhaseGauge(int memoryTier) {
        var tier = Integer.toString(memoryTier);
        var phases = java.util.Arrays.stream(WorkerPhase.Phase.values())
            .map(p -> java.util.Map.entry(p, Attributes.of(PHASE, p.label(), MEMORY_TIER, tier))).toList();
        return getMeterProvider().get(scopeName).gaugeBuilder(WORKER_PHASE).setUnit("count").ofLongs()
            .buildWithCallback(m -> {
                var current = WorkerPhase.current();
                phases.forEach(e -> m.record(e.getKey() == current ? 1 : 0, e.getValue()));
            });
    }

    /**
     * Counts work items this worker escalated to a higher memory tier after an OOM verdict; the {@code tier}
     * dimension is the tier escalated TO, so dashboards can chart the climb per level.
     */
    public IntConsumer makeEscalationRecorder() {
        return makeTierCounter(WORK_ITEMS_ESCALATED);
    }

    public static final String DIRTY_LEASES_RELEASED = "dirtyLeasesReleased";

    /**
     * Counts dirty leases the startup sweep released at the SAME tier (a dead predecessor without OOM
     * evidence — the non-escalating counterpart of {@link #WORK_ITEMS_ESCALATED}); the {@code tier}
     * dimension is the tier the lease stayed at. Together the two counters chart every sweep decision.
     */
    public IntConsumer makeDirtyLeaseReleaseRecorder() {
        return makeTierCounter(DIRTY_LEASES_RELEASED);
    }

    private IntConsumer makeTierCounter(String metricName) {
        var counter = getMeterProvider().get(scopeName).counterBuilder(metricName)
            .setUnit("count").build();
        return tier -> counter.add(1, Attributes.of(MEMORY_TIER, Integer.toString(tier)));
    }

    /**
     * Instruments for one target's bulk flow control, with gauges reading {@code budget} and {@code limit} (either
     * may be null). Create once per process and target.
     */
    public BulkFlowInstruments makeBulkFlowInstruments(RetryBudget budget, AdaptiveLimit limit) {
        return new BulkFlowInstruments(getMeterProvider().get(scopeName), budget, limit);
    }
}
