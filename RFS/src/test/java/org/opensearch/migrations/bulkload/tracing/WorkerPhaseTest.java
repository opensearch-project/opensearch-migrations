package org.opensearch.migrations.bulkload.tracing;

import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkerPhaseTest {
    @AfterEach
    void reset() {
        WorkerPhase.reset();
    }

    @org.junit.jupiter.api.BeforeEach
    void startFresh() {
        WorkerPhase.reset();
    }

    private static Map<String, Long> phaseGauge(DocumentMigrationTestContext context) {
        MetricData metric = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .filter(m -> m.getName().equals(BaseRootRfsContext.WORKER_PHASE)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + BaseRootRfsContext.WORKER_PHASE));
        return metric.getLongGaugeData().getPoints().stream().collect(Collectors.toMap(
            p -> p.getAttributes().get(BaseRootRfsContext.PHASE), LongPointData::getValue));
    }

    @Test
    void theGaugeReportsOneForTheCurrentPhaseAndZeroForTheRest() throws Exception {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        try (var gauge = context.makeWorkerPhaseGauge()) {
            WorkerPhase.enter(WorkerPhase.Phase.MIGRATING);
            var values = phaseGauge(context);
            Assertions.assertEquals(WorkerPhase.Phase.values().length, values.size(), values.toString());
            Assertions.assertEquals(1L, values.get("migrating"));
            Assertions.assertEquals(0L, values.get("downloading_shard"));
            Assertions.assertEquals(1L, values.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    @Test
    void eachTransitionIsLoggedWithThePreviousPhasesDurationOnce() throws Exception {
        try (var logs = new CloseableLogSetup(WorkerPhase.class.getName())) {
            WorkerPhase.enter(WorkerPhase.Phase.ACQUIRING_WORK);
            WorkerPhase.enter(WorkerPhase.Phase.DOWNLOADING_SHARD);
            WorkerPhase.enter(WorkerPhase.Phase.DOWNLOADING_SHARD);
            WorkerPhase.enter(WorkerPhase.Phase.MIGRATING);

            var lines = logs.getLogEvents();
            Assertions.assertEquals(3, lines.size(), lines.toString());
            Assertions.assertTrue(lines.get(1).contains("acquiring_work -> downloading_shard (acquiring_work took"),
                lines.get(1));
            Assertions.assertEquals(WorkerPhase.Phase.MIGRATING, WorkerPhase.current());
        }
    }

    @Test
    void startingIsTimedFromWhenTheJvmStarted() throws Exception {
        try (var logs = new CloseableLogSetup(WorkerPhase.class.getName())) {
            long uptimeBefore = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            WorkerPhase.enter(WorkerPhase.Phase.ACQUIRING_WORK);
            long uptimeAfter = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            var line = logs.getLogEvents().get(0);
            long took = Long.parseLong(line.replaceAll(".*starting took (\\d+) ms.*", "$1"));
            // The whole time since the JVM started, give or take the clocks' granularity.
            Assertions.assertTrue(took >= uptimeBefore - 50 && took <= uptimeAfter + 50,
                line + " with uptime " + uptimeBefore + "-" + uptimeAfter);
        }
    }
}
