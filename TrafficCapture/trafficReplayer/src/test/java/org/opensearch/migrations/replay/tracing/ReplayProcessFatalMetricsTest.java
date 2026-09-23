package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest ReplayProcessFatalHandler TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

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