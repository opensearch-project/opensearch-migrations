package org.opensearch.migrations;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration.WorkItem;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkItemMetricsReporterTest {
    @Test
    void includesIdentityAndPreservesUnknownCounts() {
        var work = new WorkItem("idx", 2, 42L);
        var metrics = RfsMigrateDocuments.committedMetricsReporter("snapshot", null, null)
            .apply(work, new WorkItemCursor(43, MigrationMetrics.ZERO, true, null));
        assertEquals(work.toString(), metrics.get("workItemId"));
        assertEquals("snapshot", metrics.get("snapshotName"));
        assertEquals(42L, ((Number) metrics.get("fromPosition")).longValue());
        assertEquals(43L, ((Number) metrics.get("nextPosition")).longValue());
        assertTrue((Boolean) metrics.get("shardComplete"));
        var snapshot = (Map<?, ?>) ((Map<?, ?>) metrics.get("metrics")).get("snapshot");
        assertNull(snapshot.get("liveLuceneDocCount"));
        assertNull(snapshot.get("previousLiveLuceneDocCount"));
        assertNull(metrics.get("previousSnapshotName"));
        assertNull(metrics.get("deltaMode"));
    }

    @Test
    void includesDeltaCountsAndAdditiveRequestTotals() {
        var totals = new MigrationMetrics(new MigrationMetrics.Source(3, 1, 300),
            new MigrationMetrics.Requests(2, 3, 1, 900, 90));
        var metrics = RfsMigrateDocuments.committedMetricsReporter("after", "before", DeltaMode.UPDATES_AND_DELETES)
            .apply(new WorkItem("idx", 0, 0L), new WorkItemCursor(4, totals, true, 16L, 14L));
        assertEquals("before", metrics.get("previousSnapshotName"));
        assertEquals("UPDATES_AND_DELETES", metrics.get("deltaMode"));
        var groups = (Map<?, ?>) metrics.get("metrics");
        var snapshot = (Map<?, ?>) groups.get("snapshot");
        assertEquals(16L, ((Number) snapshot.get("liveLuceneDocCount")).longValue());
        assertEquals(14L, ((Number) snapshot.get("previousLiveLuceneDocCount")).longValue());
        var requests = (Map<?, ?>) groups.get("requests");
        assertEquals(3L, ((Number) requests.get("total")).longValue());
        assertEquals(1L, ((Number) requests.get("retries")).longValue());
        assertEquals(90L, ((Number) requests.get("durationMillis")).longValue());
    }

    @Test
    void publishesSnapshotCountsOnlyOnShardCompletion() {
        var reporter = RfsMigrateDocuments.committedMetricsReporter("snapshot", null, null);
        var work = new WorkItem("idx", 0, 0L);
        var checkpoint = reporter.apply(work, new WorkItemCursor(1, MigrationMetrics.ZERO, false, 14L));
        assertNull(((Map<?, ?>) checkpoint.get("metrics")).get("snapshot"));
        var completed = reporter.apply(work, new WorkItemCursor(0, MigrationMetrics.ZERO, true, 0L));
        var snapshot = (Map<?, ?>) ((Map<?, ?>) completed.get("metrics")).get("snapshot");
        assertEquals(0L, ((Number) snapshot.get("liveLuceneDocCount")).longValue());
    }
}
