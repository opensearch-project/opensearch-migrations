package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ReplayTransaction . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

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
    private static final String TRANSACTIONS_UNIT = "transactions";

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ACTIVE_PHASE = "replayTransactionActivePhase";
        public static final String RUNWAY_STATE = "replayTransactionRunwayState";
        public static final String RUNWAY_LOSS = "replayTransactionRunwayLoss";
        public static final String TERMINAL_OUTCOME = "replayTransactionTerminalOutcome";
    }

    private final LongUpDownCounter activePhase;
    private final LongUpDownCounter runwayState;
    private final LongCounter runwayLoss;
    private final LongCounter terminalOutcome;

    public ReplayTransactionMetrics(@NonNull Meter meter) {
        activePhase = meter.upDownCounterBuilder(MetricNames.ACTIVE_PHASE)
            .setUnit(TRANSACTIONS_UNIT)
            .build();
        runwayState = meter.upDownCounterBuilder(MetricNames.RUNWAY_STATE)
            .setUnit(TRANSACTIONS_UNIT)
            .build();
        runwayLoss = meter.counterBuilder(MetricNames.RUNWAY_LOSS)
            .setUnit("events")
            .build();
        terminalOutcome = meter.counterBuilder(MetricNames.TERMINAL_OUTCOME)
            .setUnit(TRANSACTIONS_UNIT)
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
}

*/
// REBUILD-LIMBO-END(G2)