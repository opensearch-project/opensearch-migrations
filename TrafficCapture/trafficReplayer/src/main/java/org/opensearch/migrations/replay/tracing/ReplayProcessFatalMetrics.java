package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ReplayProcessFatalHandler . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import org.opensearch.migrations.replay.ReplayProcessFatalHandler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class ReplayProcessFatalMetrics implements ReplayProcessFatalHandler.Metrics {
    public static final AttributeKey<String> REASON_ATTRIBUTE = AttributeKey.stringKey("reason");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String FATAL_FAILURES = "replayFatalFailures";
    }

    private final LongCounter fatalFailures;

    public ReplayProcessFatalMetrics(@NonNull Meter meter) {
        fatalFailures = meter.counterBuilder(MetricNames.FATAL_FAILURES)
            .setUnit("failures")
            .build();
    }

    @Override
    public void fatalFailure(@NonNull ReplayProcessFatalHandler.Reason reason) {
        fatalFailures.add(1, Attributes.of(REASON_ATTRIBUTE, reason.metricLabel()));
    }
}

*/
// REBUILD-LIMBO-END(G2)