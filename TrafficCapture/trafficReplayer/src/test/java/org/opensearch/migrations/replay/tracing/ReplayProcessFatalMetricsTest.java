package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G10) -- the inherited metric test remains marked through the final completeness
// sweep while the live replacement below exercises the supervisor-owned reason type.
// REBUILD-LIMBO-START(G10)
/*

import org.opensearch.migrations.replay.ReplayProcessFatalHandler;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProcessFatalMetricsTest extends InstrumentationTest {
    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void recordsTheFatalReasonUsedForAlarming() {
        rootContext.getReplayProcessFatalMetrics()
            .fatalFailure(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED);

        var point = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(metric -> metric.getName().equals(ReplayProcessFatalMetrics.MetricNames.FATAL_FAILURES))
            .findFirst()
            .orElseThrow()
            .getLongSumData()
            .getPoints()
            .stream()
            .filter(candidate -> ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.metricLabel().equals(
                candidate.getAttributes().get(ReplayProcessFatalMetrics.REASON_ATTRIBUTE)
            ))
            .findFirst()
            .orElseThrow();

        Assertions.assertEquals(1, point.getValue());
    }
}

*/
// REBUILD-LIMBO-END(G10)

import org.opensearch.migrations.replay.ProcessSupervisor;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProcessFatalMetricsTest {
    @Test
    void recordsTheFatalReasonUsedForAlarming() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            rootContext.getReplayProcessFatalMetrics()
                .fatalFailure(ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED);

            var point = telemetry.getFinishedMetrics()
                .stream()
                .filter(metric -> metric.getName().equals(ReplayProcessFatalMetrics.MetricNames.FATAL_FAILURES))
                .findFirst()
                .orElseThrow()
                .getLongSumData()
                .getPoints()
                .stream()
                .filter(candidate -> ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED.metricLabel().equals(
                    candidate.getAttributes().get(ReplayProcessFatalMetrics.REASON_ATTRIBUTE)
                ))
                .findFirst()
                .orElseThrow();

            Assertions.assertEquals(1, point.getValue());
        }
    }
}
