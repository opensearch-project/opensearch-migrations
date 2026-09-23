package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: TargetExchangeState . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class TargetExchangeStateMetrics implements TargetExchangeState.Metrics {
    public static final AttributeKey<String> PHASE_ATTRIBUTE = AttributeKey.stringKey("phase");
    public static final AttributeKey<String> STATE_ATTRIBUTE = AttributeKey.stringKey("state");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ACTIVE_PHASE = "targetExchangeActivePhase";
        public static final String CHANNEL_STATE = "targetChannelState";
    }

    private final LongUpDownCounter activePhase;
    private final LongUpDownCounter channelState;

    public TargetExchangeStateMetrics(@NonNull Meter meter) {
        activePhase = meter.upDownCounterBuilder(MetricNames.ACTIVE_PHASE)
            .setUnit("exchanges")
            .build();
        channelState = meter.upDownCounterBuilder(MetricNames.CHANNEL_STATE)
            .setUnit("channels")
            .build();
    }

    @Override
    public void phaseChanged(@NonNull TargetExchangeState.Phase phase, int delta) {
        activePhase.add(delta, Attributes.of(PHASE_ATTRIBUTE, phase.metricLabel()));
    }

    @Override
    public void channelStateChanged(@NonNull TargetExchangeState.ChannelState state, int delta) {
        channelState.add(delta, Attributes.of(STATE_ATTRIBUTE, state.metricLabel()));
    }
}

*/
// REBUILD-LIMBO-END(G2)