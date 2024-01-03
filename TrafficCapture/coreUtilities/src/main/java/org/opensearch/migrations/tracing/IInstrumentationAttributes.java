package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.time.Duration;
import java.util.ArrayList;

public interface IInstrumentationAttributes {
    String getScopeName();
    IInstrumentationAttributes getEnclosingScope();
    @NonNull IInstrumentConstructor getRootInstrumentationScope();
    default Span getCurrentSpan() { return null; }

    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder;
    }

    default Attributes getPopulatedAttributes() {
        return getPopulatedAttributesBuilder().build();
    }

    default AttributesBuilder getPopulatedAttributesBuilder() {
        var currentObj = this;
        var stack = new ArrayList<IInstrumentationAttributes>();
        var builder = Attributes.builder();
        while (currentObj != null) {
            stack.add(currentObj);
            currentObj = currentObj.getEnclosingScope();
        }
        // reverse the order so that the lowest attribute scopes will overwrite the upper ones if there were conflicts
        for (int i=stack.size()-1; i>=0; --i) {
            builder = stack.get(i).fillAttributes(builder);
        }
        return builder;
    }

    default void meterIncrementEvent(String eventName) {
        getRootInstrumentationScope().buildMeter(this).meterIncrementEvent(eventName);
    }
    default void meterIncrementEvent(String eventName, long increment) {
        getRootInstrumentationScope().buildMeter(this).meterIncrementEvent(eventName, increment);
    }
    default void meterDeltaEvent(String eventName, long delta) {
        getRootInstrumentationScope().buildMeter(this).meterDeltaEvent(eventName, delta);
    }
    default void meterHistogramMicros(String eventName, Duration value) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMicros(eventName, value);
    }
    default void meterHistogramMillis(String eventName, Duration value) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMillis(eventName, value);
    }
    default void meterHistogram(String eventName, String units, long value) {
        getRootInstrumentationScope().buildMeter(this).meterHistogram(eventName, units, value);
    }
    default void meterHistogramMicros(String eventName) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMicros(eventName);
    }
    default void meterHistogramMillis(String eventName) {
        getRootInstrumentationScope().buildMeter(this).meterHistogramMillis(eventName);
    }

}
