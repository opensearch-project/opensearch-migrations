package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    Instant getStartTime();

    default void meterHistogramMillis(DoubleHistogram histogram) {
        meterHistogramMillis(histogram, Duration.between(getStartTime(), Instant.now()));
    }
    default void meterHistogramMillis(DoubleHistogram histogram, Duration value) {
        meterHistogramMillis(histogram, value, null);
    }
    default void meterHistogramMillis(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogramMillis(histogram, Duration.between(getStartTime(), Instant.now()),
                attributesBuilder);
    }
    default void meterHistogramMillis(DoubleHistogram histogram, Duration value, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, value.toNanos()/1_000_000.0, attributesBuilder);
    }
    default void meterHistogram(DoubleHistogram histogram, double value) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            histogram.record(value);
        }
    }
    default void meterHistogram(DoubleHistogram histogram, double value, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            histogram.record(value);
        }
    }
}
