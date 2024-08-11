package org.opensearch.migrations.tracing;

import java.time.Duration;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;

import lombok.NonNull;

public interface IInstrumentationAttributes {
    AttributeKey<Boolean> HAD_EXCEPTION_KEY = AttributeKey.booleanKey("hadException");

    IInstrumentationAttributes getEnclosingScope();

    CommonMetricInstruments getMetrics();

    Throwable getObservedExceptionToIncludeInMetrics();

    default @NonNull Attributes getPopulatedMetricAttributes(AttributesBuilder attributesBuilder) {
        return attributesBuilder.build();
    }

    default void addCaughtException(Throwable e) {
        addTraceException(e, false);
    }

    default void addTraceException(Throwable e, boolean exceptionIsPropagating) {
        meterIncrementEvent(getMetrics().exceptionCounter);
    }

    default void meterIncrementEvent(LongCounter c) {
        meterIncrementEvent(c, 1);
    }

    default void meterIncrementEvent(LongCounter c, long increment) {
        meterIncrementEvent(c, increment, Attributes.builder());
    }

    default void meterIncrementEvent(LongCounter c, long increment, AttributesBuilder attributesBuilder) {
        c.add(increment, getPopulatedMetricAttributes(attributesBuilder));
    }

    default void meterDeltaEvent(LongUpDownCounter c, long delta) {
        meterDeltaEvent(c, delta, Attributes.builder());
    }

    default void meterDeltaEvent(LongUpDownCounter c, long delta, AttributesBuilder attributesBuilder) {
        var attributes = getPopulatedMetricAttributes(attributesBuilder);
        c.add(delta, attributes);
    }

    default void meterHistogramMillis(DoubleHistogram histogram, Duration value) {
        meterHistogram(histogram, value.toNanos() / 1_000_000.0);
    }

    default void meterHistogramMillis(DoubleHistogram histogram, Duration value, AttributesBuilder attributesBuilder) {
        meterHistogram(histogram, value.toNanos() / 1_000_000.0, attributesBuilder);
    }

    default void meterHistogram(DoubleHistogram histogram, double value) {
        meterHistogram(histogram, value, Attributes.builder());
    }

    default void meterHistogram(DoubleHistogram histogram, double value, AttributesBuilder attributesBuilder) {
        histogram.record(value, getPopulatedMetricAttributes(attributesBuilder));
    }

    default void meterHistogram(LongHistogram histogram, long value) {
        meterHistogram(histogram, value, Attributes.builder());
    }

    default void meterHistogram(LongHistogram histogram, long value, AttributesBuilder attributesBuilder) {
        histogram.record(value, getPopulatedMetricAttributes(attributesBuilder));
    }

}
