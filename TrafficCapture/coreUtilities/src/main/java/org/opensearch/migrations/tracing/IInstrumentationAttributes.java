package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.util.ArrayList;

public interface IInstrumentationAttributes<S extends IInstrumentConstructor> {
    String getScopeName();
    IInstrumentationAttributes<S> getEnclosingScope();
    @NonNull S getRootInstrumentationScope();
    default Span getCurrentSpan() { return null; }

    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder;
    }

    default Attributes getPopulatedAttributes(AttributesBuilder builder) {
        return getPopulatedAttributesBuilder(builder).build();
    }

    default AttributesBuilder getPopulatedAttributesBuilder(AttributesBuilder builder) {
        var currentObj = this;
        var stack = new ArrayList<IInstrumentationAttributes<S>>();
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
        meterIncrementEvent(c, Attributes.builder());
    }
    default void meterIncrementEvent(LongCounter c, AttributesBuilder attributesBuilder) {
        meterIncrementEvent(c, 1, attributesBuilder);
    }
    default void meterIncrementEvent(LongCounter c, long increment) {
        meterIncrementEvent (c, increment, Attributes.builder());
    }
    default void meterIncrementEvent(LongCounter c, long increment, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            c.add(increment);
        }
    }
    default void meterDeltaEvent(LongUpDownCounter c, long delta) {
        meterDeltaEvent(c, delta, Attributes.builder());
    }
    default void meterDeltaEvent(LongUpDownCounter c, long delta, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            c.add(delta);
        }
    }

}
