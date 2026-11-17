package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.ProcessSupervisor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Counts process-wide replay termination sequences by their bounded failure category.
 *
 * <p>The process supervisor accepts only the first fatal signal, so each increment represents the initiating
 * reason whose diagnostics and exit code won the termination race. Owner names and exception text are kept
 * out of metric attributes to avoid unbounded cardinality and remain available in the fatal log instead.</p>
 */
public final class ReplayProcessFatalMetrics implements ProcessSupervisor.Metrics {
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
    public void fatalFailure(@NonNull ProcessSupervisor.Reason reason) {
        fatalFailures.add(1, Attributes.of(REASON_ATTRIBUTE, reason.metricLabel()));
    }
}
