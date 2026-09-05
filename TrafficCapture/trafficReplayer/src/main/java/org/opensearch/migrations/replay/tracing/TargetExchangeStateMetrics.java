package org.opensearch.migrations.replay.tracing;

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
