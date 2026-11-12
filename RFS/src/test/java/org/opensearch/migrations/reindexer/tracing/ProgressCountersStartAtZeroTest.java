package org.opensearch.migrations.reindexer.tracing;

import java.util.Collection;

import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A worker migrates one shard and asks for work about once, so these counters must exist at 0 from the start:
 * a Prometheus series that first appears at a nonzero value contributes nothing to rate() or increase().
 */
class ProgressCountersStartAtZeroTest {
    @ParameterizedTest
    @ValueSource(strings = {"pipelineDocsMigrated", "pipelineBytesMigrated", "nextWorkAssignedCount",
        "noNextWorkAvailableCount", "bulkDocumentsFailed"})
    void existsAtZeroBeforeAnyWork(String name) {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        Collection<MetricData> metrics = context.inMemoryInstrumentationBundle.getFinishedMetrics();
        var metric = metrics.stream().filter(m -> m.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " not exported; exported: "
                + metrics.stream().map(MetricData::getName).toList()));
        Assertions.assertEquals(0L, metric.getLongSumData().getPoints().stream()
            .mapToLong(LongPointData::getValue).sum());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bulkDocsGaveUp", "bulkDocsTargetUnavailable", "bulkLimitCuts",
        "bulkBatchSplits", "bulkLimitQuietResets"})
    void rareBulkFlowEventsExistAtZero(String name) {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        context.makeBulkFlowInstruments(null, null);
        Collection<MetricData> metrics = context.inMemoryInstrumentationBundle.getFinishedMetrics();
        var metric = metrics.stream().filter(m -> m.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " not exported; exported: "
                + metrics.stream().map(MetricData::getName).toList()));
        Assertions.assertEquals(0L, metric.getLongSumData().getPoints().stream()
            .mapToLong(LongPointData::getValue).sum());
    }

    @org.junit.jupiter.api.Test
    void everyLimitCutSignalAndSplitReasonHasASeries() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        context.makeBulkFlowInstruments(null, null);
        var metrics = context.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(java.util.Set.of("throttled_429", "admission_rejected", "timeout"),
            labels(metrics, "bulkLimitCuts", "signal"));
        Assertions.assertEquals(java.util.Set.of("status", "no_response", "too_large"),
            labels(metrics, "bulkBatchSplits", "reason"));
    }

    private static java.util.Set<String> labels(Collection<MetricData> metrics, String name, String key) {
        return metrics.stream().filter(m -> m.getName().equals(name))
            .flatMap(m -> m.getLongSumData().getPoints().stream())
            .map(p -> p.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(key)))
            .collect(java.util.stream.Collectors.toSet());
    }
}
