package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    Instant getStartTime();

    default Duration getSpanDuration() {
        return Duration.between(getStartTime(), Instant.now());
    }

    default void meterHistogramMillis(DoubleHistogram histogram) {
        meterHistogramMillis(histogram, getSpanDuration());
    }

    default void meterHistogramMillis(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        meterHistogramMillis(histogram, getSpanDuration(), attributesBuilder);
    }
}
