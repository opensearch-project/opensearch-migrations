package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes<S extends IInstrumentConstructor> extends IInstrumentationAttributes<S> {
    Instant getStartTime();


    default void meterHistogramMillis(DoubleHistogram histogram) {
        meterHistogramMillis(histogram, Attributes.builder());
    }
    default void meterHistogramMillis(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeterClosure(this).meterHistogramMillis(histogram, attributesBuilder);
    }
    default void meterHistogramMillis(DoubleHistogram histogram, Duration value) {
        meterHistogramMillis(histogram, value, Attributes.builder());
    }
    default void meterHistogramMillis(DoubleHistogram histogram, Duration value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeterClosure(this).meterHistogramMillis(histogram, value, attributesBuilder);
    }

    default void meterHistogramMicros(DoubleHistogram histogram, Duration value) {
        meterHistogramMicros(histogram, value, Attributes.builder());
    }
    default void meterHistogramMicros(DoubleHistogram histogram, Duration value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeterClosure(this).meterHistogramMicros(histogram, value, attributesBuilder);
    }
    default void meterHistogramMicros(DoubleHistogram histogram) {
        meterHistogramMicros(histogram, Attributes.builder());
    }
    default void meterHistogramMicros(DoubleHistogram histogram, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeterClosure(this)
                .meterHistogramMicros(histogram, attributesBuilder);
    }

    default void meterHistogram(DoubleHistogram histogram, long value) {
        meterHistogram(histogram, value, Attributes.builder());
    }
    default void meterHistogram(DoubleHistogram histogram, long value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeterClosure(this).meterHistogram(histogram, value, attributesBuilder);
    }

}
