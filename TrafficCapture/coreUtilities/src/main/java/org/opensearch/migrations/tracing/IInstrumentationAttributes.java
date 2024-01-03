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

    default Attributes getPopulatedAttributes(AttributesBuilder builder) {
        return getPopulatedAttributesBuilder(builder).build();
    }

    default AttributesBuilder getPopulatedAttributesBuilder(AttributesBuilder builder) {
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

    default void meterIncrementEvent(String eventName) {
        meterIncrementEvent(eventName, Attributes.builder());
    }
    default void meterIncrementEvent(String eventName, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildSimpleMeter(this).meterIncrementEvent(eventName, attributesBuilder);
    }
    default void meterIncrementEvent(String eventName, long increment) {
        meterIncrementEvent (eventName, increment, Attributes.builder());
    }
    default void meterIncrementEvent(String eventName, long increment, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildSimpleMeter(this)
                .meterIncrementEvent(eventName, increment, attributesBuilder);
    }
    default void meterDeltaEvent(String eventName, long delta) {
        meterDeltaEvent(eventName, delta, Attributes.builder());
    }
    default void meterDeltaEvent(String eventName, long delta, AttributesBuilder attributesBuilder) {
        getRootInstrumentationScope().buildSimpleMeter(this).meterDeltaEvent(eventName, delta, attributesBuilder);
    }
}
