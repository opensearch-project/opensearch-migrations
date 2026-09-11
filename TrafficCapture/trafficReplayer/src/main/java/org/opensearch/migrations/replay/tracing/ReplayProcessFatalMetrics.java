package org.opensearch.migrations.replay.tracing;

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
