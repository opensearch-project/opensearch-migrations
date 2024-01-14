package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.util.ArrayList;

public interface IInstrumentationAttributes {
    AttributeKey<Boolean> HAD_EXCEPTION_KEY = AttributeKey.booleanKey("hadException");

    IInstrumentationAttributes getEnclosingScope();
    default Span getCurrentSpan() { return null; }

    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder;
    }

    Exception getObservedExceptionToIncludeInMetrics();
    void setObservedExceptionToIncludeInMetrics(Exception e);

    default @NonNull Attributes getPopulatedMetricAttributes() {
        final var e = getObservedExceptionToIncludeInMetrics();
        var b = Attributes.builder();
        return e == null ? b.build() : b.put(HAD_EXCEPTION_KEY, true).build();
    }

    default Attributes getPopulatedSpanAttributes() {
        return getPopulatedSpanAttributes(Attributes.builder());
    }

    default Attributes getPopulatedSpanAttributes(AttributesBuilder builder) {
        return getPopulatedSpanAttributesBuilder(builder).build();
    }

    default AttributesBuilder getPopulatedSpanAttributesBuilder(AttributesBuilder builder) {
        var currentObj = this;
        var stack = new ArrayList<IInstrumentationAttributes>();
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

    default void meterIncrementEvent(LongCounter c) {
        meterIncrementEvent(c, 1);
    }
    default void meterIncrementEvent(LongCounter c, long increment) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            c.add(increment, getPopulatedMetricAttributes());
        }
    }
    default void meterDeltaEvent(LongUpDownCounter c, long delta) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            c.add(delta, getPopulatedMetricAttributes());
        }
    }
}
