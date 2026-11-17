package org.opensearch.migrations.replay.tracing;


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
