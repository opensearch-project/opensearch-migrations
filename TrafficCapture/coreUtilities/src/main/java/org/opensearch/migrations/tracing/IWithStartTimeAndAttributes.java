package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

import java.time.Duration;
import java.time.Instant;

public interface IWithStartTimeAndAttributes extends IInstrumentationAttributes {
    Instant getStartTime();


    default void meterHistogramMicros(String eventName, Duration value) {
        meterHistogramMicros(eventName, value, Attributes.builder());
    }
    default void meterHistogramMicros(String eventName, Duration value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMicros(eventName, value, attributesBuilder);
    }
    default void meterHistogramMillis(String eventName, Duration value) {
        meterHistogramMillis(eventName, value, Attributes.builder());
    }
    default void meterHistogramMillis(String eventName, Duration value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMillis(eventName, value, attributesBuilder);
    }
    default void meterHistogram(String eventName, String units, long value) {
        meterHistogram(eventName, units, value, Attributes.builder());
    }
    default void meterHistogram(String eventName, String units, long value, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeter(this).meterHistogram(eventName, units, value, attributesBuilder);
    }
    default void meterHistogramMicros(String eventName) {
        meterHistogramMicros(eventName, Attributes.builder());
    }
    default void meterHistogramMicros(String eventName, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMicros(eventName, attributesBuilder);
    }
    default void meterHistogramMillis(String eventName) {
        meterHistogramMillis(eventName, Attributes.builder());
    }
    default void meterHistogramMillis(String eventName, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMillis(eventName, attributesBuilder);
    }
}
