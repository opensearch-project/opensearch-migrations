package org.opensearch.migrations.reindexer.tracing;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PipelineInstrumentsTest {

    @Test
    void everyPipelineInstrumentReports() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var metrics = context.pipelineInstruments;

        metrics.onConfigured(20_000, 10_000_000, 60, 4, 4_096);
        metrics.onPreparationChunkQueued(10, 1_000);
        metrics.onPreparationChunkStarted(10, 1_000, 2);
        metrics.onPreparationChunkFinished(10, 1_000, 1_200, 3, true);
        metrics.onPreparationChunkReleased(10, 1_200, 4);
        metrics.onPackingChunk(10, 5);
        metrics.onBatchPacked(10, 1_200, 50, 75);
        metrics.onRequestGroupStarted(1, 10, 1_200, 6);
        metrics.onRequestGroupFinished(10, 1_200, 7, true);
        metrics.onCheckpoint(10, 1_000);

        Set<String> reported = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .map(MetricData::getName)
            .collect(Collectors.toSet());
        var missing = new TreeSet<String>();
        for (var field : PipelineInstruments.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    missing.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        missing.removeAll(reported);
        Assertions.assertEquals(Set.of(), missing, "reported: " + reported);
    }
}
