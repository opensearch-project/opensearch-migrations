/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.time.Duration;
import java.util.Locale;

import org.opensearch.migrations.replay.sink.TupleWriter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Tracks logical tuple writes from acceptance through their durable or terminal outcome.
 *
 * <p>The counters satisfy: accepted = durable + cancelled + terminal failures + pending.
 * Failure attributes come from a fixed enum and contain no traffic identity.</p>
 */
public final class TupleDurabilityMetrics implements TupleWriter.Metrics {
    public static final AttributeKey<String> FAILURE_REASON_ATTRIBUTE =
        AttributeKey.stringKey("failureReason");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String WRITES_ACCEPTED = "tupleDurabilityWritesAccepted";
        public static final String WRITES_PENDING = "tupleDurabilityWritesPending";
        public static final String RETRIES = "tupleDurabilityRetries";
        public static final String TERMINAL_FAILURES = "tupleDurabilityTerminalFailures";
        public static final String WRITES_DURABLE = "tupleDurabilityWritesDurable";
        public static final String WRITES_CANCELLED = "tupleDurabilityWritesCancelled";
        public static final String WRITE_LATENCY = "tupleDurabilityWriteLatency";
    }

    private final LongCounter writesAccepted;
    private final LongUpDownCounter writesPending;
    private final LongCounter retries;
    private final LongCounter terminalFailures;
    private final LongCounter writesDurable;
    private final LongCounter writesCancelled;
    private final DoubleHistogram writeLatency;

    public TupleDurabilityMetrics(@NonNull Meter meter) {
        writesAccepted = meter.counterBuilder(MetricNames.WRITES_ACCEPTED)
            .setDescription("Logical tuple writes accepted by the tuple writer API")
            .setUnit("writes")
            .build();
        writesPending = meter.upDownCounterBuilder(MetricNames.WRITES_PENDING)
            .setDescription("Accepted logical tuple writes without a terminal outcome")
            .setUnit("writes")
            .build();
        retries = meter.counterBuilder(MetricNames.RETRIES)
            .setDescription("Physical tuple write retries successfully scheduled")
            .setUnit("retries")
            .build();
        terminalFailures = meter.counterBuilder(MetricNames.TERMINAL_FAILURES)
            .setDescription("Logical tuple writes that ended in a fatal writer failure")
            .setUnit("writes")
            .build();
        writesDurable = meter.counterBuilder(MetricNames.WRITES_DURABLE)
            .setDescription("Logical tuple writes completed durably, including intentional drops")
            .setUnit("writes")
            .build();
        writesCancelled = meter.counterBuilder(MetricNames.WRITES_CANCELLED)
            .setDescription("Logical tuple writes cancelled before durability")
            .setUnit("writes")
            .build();
        writeLatency = meter.histogramBuilder(MetricNames.WRITE_LATENCY)
            .setDescription("Elapsed time from logical write acceptance to durable completion")
            .setUnit("ms")
            .build();
    }

    @Override
    public void writeAccepted() {
        writesAccepted.add(1);
        writesPending.add(1);
    }

    @Override
    public void retryScheduled() {
        retries.add(1);
    }

    @Override
    public void writeDurable(@NonNull Duration latency) {
        writesPending.add(-1);
        writesDurable.add(1);
        writeLatency.record(Math.max(0, latency.toNanos()) / 1_000_000.0);
    }

    @Override
    public void writeCancelled() {
        writesPending.add(-1);
        writesCancelled.add(1);
    }

    @Override
    public void terminalFailure(@NonNull TupleWriter.FailureReason reason) {
        writesPending.add(-1);
        terminalFailures.add(
            1,
            Attributes.of(
                FAILURE_REASON_ATTRIBUTE,
                reason.name().toLowerCase(Locale.ROOT)
            )
        );
    }
}
