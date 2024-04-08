package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    long getStartTimeNano();
    Instant getStartTimeInstant();

    default Duration getSpanDuration() {
        return Duration.ofNanos(System.nanoTime() - getStartTimeNano());
    }

    default void meterHistogramMillis(DoubleHistogram histogram) {
        meterHistogramMillis(histogram, getSpanDuration());
    }

    default void meterHistogramMillis(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogramMillis(histogram, getSpanDuration(), attributesBuilder);
    }
}
