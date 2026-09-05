package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class ReplayTransactionMetrics implements ReplayTransaction.Metrics {
    public static final AttributeKey<String> PHASE_ATTRIBUTE = AttributeKey.stringKey("phase");
    public static final AttributeKey<String> STATE_ATTRIBUTE = AttributeKey.stringKey("state");
    public static final AttributeKey<String> REASON_ATTRIBUTE = AttributeKey.stringKey("reason");
    public static final AttributeKey<String> OUTCOME_ATTRIBUTE = AttributeKey.stringKey("outcome");
    public static final AttributeKey<String> ACTION_ATTRIBUTE = AttributeKey.stringKey("action");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ACTIVE_PHASE = "replayTransactionActivePhase";
        public static final String RUNWAY_STATE = "replayTransactionRunwayState";
        public static final String RUNWAY_LOSS = "replayTransactionRunwayLoss";
        public static final String TERMINAL_OUTCOME = "replayTransactionTerminalOutcome";
        public static final String DISPOSITION = "replayTransactionDisposition";
    }

    private final LongUpDownCounter activePhase;
    private final LongUpDownCounter runwayState;
    private final LongCounter runwayLoss;
    private final LongCounter terminalOutcome;
    private final LongCounter disposition;

    public ReplayTransactionMetrics(@NonNull Meter meter) {
        activePhase = meter.upDownCounterBuilder(MetricNames.ACTIVE_PHASE)
            .setUnit("transactions")
            .build();
        runwayState = meter.upDownCounterBuilder(MetricNames.RUNWAY_STATE)
            .setUnit("transactions")
            .build();
        runwayLoss = meter.counterBuilder(MetricNames.RUNWAY_LOSS)
            .setUnit("events")
            .build();
        terminalOutcome = meter.counterBuilder(MetricNames.TERMINAL_OUTCOME)
            .setUnit("transactions")
            .build();
        disposition = meter.counterBuilder(MetricNames.DISPOSITION)
            .setUnit("transactions")
            .build();
    }

    @Override
    public void phaseChanged(@NonNull ReplayTransaction.Phase phase, int delta) {
        activePhase.add(delta, Attributes.of(PHASE_ATTRIBUTE, phase.metricLabel()));
    }

    @Override
    public void runwayStateChanged(@NonNull ReplayTransaction.RunwayState state, int delta) {
        runwayState.add(delta, Attributes.of(STATE_ATTRIBUTE, state.metricLabel()));
    }

    @Override
    public void runwayLost(@NonNull ReplayTransaction.RunwayLossReason reason) {
        runwayLoss.add(1, Attributes.of(REASON_ATTRIBUTE, reason.metricLabel()));
    }

    @Override
    public void terminalOutcome(@NonNull ReplayTransaction.TerminalOutcome outcome) {
        terminalOutcome.add(1, Attributes.of(OUTCOME_ATTRIBUTE, outcome.metricLabel()));
    }

    @Override
    public void disposition(@NonNull RecordDisposition disposition) {
        this.disposition.add(
            1,
            Attributes.of(
                ACTION_ATTRIBUTE,
                disposition.action().metricLabel(),
                REASON_ATTRIBUTE,
                disposition.reasonCode()
            )
        );
    }
}
