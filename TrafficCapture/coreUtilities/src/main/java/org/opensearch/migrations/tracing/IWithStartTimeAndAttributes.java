package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    Instant getStartTime();

    default void meterHistogramMillis(DoubleHistogram histogram) {
        meterHistogramMillis(histogram, Duration.between(getStartTime(), Instant.now()));
    }
    default void meterHistogramMillis(DoubleHistogram histogram, Duration value) {
        meterHistogram(histogram, value.toNanos()/1_000_000.0);
    }
    default void meterHistogram(DoubleHistogram histogram, double value) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            histogram.record(value, getPopulatedMetricAttributes());
        }
    }
    default void meterHistogram(LongHistogram histogram, long value) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            histogram.record(value, getPopulatedMetricAttributes());
        }
    }
}
