package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayTransactionMetricsTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void recordsLifecycleStateAndOutcomesWithStableLabels() {
        var metrics = rootContext.getReplayTransactionMetrics();
        metrics.phaseChanged(ReplayTransaction.Phase.WRITING_EVIDENCE, 1);
        metrics.runwayStateChanged(ReplayTransaction.RunwayState.LOST, 1);
        metrics.runwayLost(ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT);
        metrics.terminalOutcome(ReplayTransaction.TerminalOutcome.RETAINED);
        metrics.disposition(new RecordDisposition.Commit("success"));
        metrics.disposition(new RecordDisposition.Retain("source-reassigned"));

        assertPoint(
            ReplayTransactionMetrics.MetricNames.ACTIVE_PHASE,
            Attributes.of(
                ReplayTransactionMetrics.PHASE_ATTRIBUTE,
                ReplayTransaction.Phase.WRITING_EVIDENCE.metricLabel()
            ),
            1
        );
        assertPoint(
            ReplayTransactionMetrics.MetricNames.RUNWAY_STATE,
            Attributes.of(
                ReplayTransactionMetrics.STATE_ATTRIBUTE,
                ReplayTransaction.RunwayState.LOST.metricLabel()
            ),
            1
        );
        assertPoint(
            ReplayTransactionMetrics.MetricNames.RUNWAY_LOSS,
            Attributes.of(
                ReplayTransactionMetrics.REASON_ATTRIBUTE,
                ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT.metricLabel()
            ),
            1
        );
        assertPoint(
            ReplayTransactionMetrics.MetricNames.TERMINAL_OUTCOME,
            Attributes.of(
                ReplayTransactionMetrics.OUTCOME_ATTRIBUTE,
                ReplayTransaction.TerminalOutcome.RETAINED.metricLabel()
            ),
            1
        );
        assertPoint(
            ReplayTransactionMetrics.MetricNames.DISPOSITION,
            Attributes.of(
                ReplayTransactionMetrics.ACTION_ATTRIBUTE,
                "commit",
                ReplayTransactionMetrics.REASON_ATTRIBUTE,
                "success"
            ),
            1
        );
        assertPoint(
            ReplayTransactionMetrics.MetricNames.DISPOSITION,
            Attributes.of(
                ReplayTransactionMetrics.ACTION_ATTRIBUTE,
                "retain",
                ReplayTransactionMetrics.REASON_ATTRIBUTE,
                "source-reassigned"
            ),
            1
        );
    }

    private void assertPoint(String metricName, Attributes attributes, long expectedValue) {
        var point = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(metric -> metric.getName().equals(metricName))
            .findFirst()
            .orElseThrow()
            .getLongSumData()
            .getPoints()
            .stream()
            .filter(candidate -> candidate.getAttributes().equals(attributes))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(expectedValue, point.getValue());
    }
}
