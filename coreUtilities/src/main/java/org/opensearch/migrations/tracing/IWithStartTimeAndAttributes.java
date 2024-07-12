package org.opensearch.migrations.tracing;

import java.time.Duration;
import java.time.Instant;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    /**
     * This is used to calculate the precise duration of the span.  System.nanoTime() is guaranteed to be monotonic
     * and not susceptible to clock fluctuations due to system time resets
     */
    long getStartTimeNano();

    /**
     * This is by some ContextTrackers to log the recorded wall-time so that it can be easier to find the event
     * within logs. Notice that if the system clock is reset (which should be rare), there could be duplicated
     * values at different points in time.
     */
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
