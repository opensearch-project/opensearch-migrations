package org.opensearch.migrations.tracing;

import java.util.ArrayDeque;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.SemanticAttributes;

import org.opensearch.migrations.Utils;

import lombok.NonNull;

public interface IScopedInstrumentationAttributes extends IWithStartTimeAndAttributes, AutoCloseable {

    String getActivityName();

    @Override
    IScopedInstrumentationAttributes getEnclosingScope();

    @Override
    CommonScopedMetricInstruments getMetrics();

    @NonNull
    Span getCurrentSpan();

    @NonNull
    IContextTracker getContextTracker();

    default Attributes getPopulatedSpanAttributes() {
        return getPopulatedSpanAttributesBuilder().build();
    }

    default AttributesBuilder getPopulatedSpanAttributesBuilder() {
        IInstrumentationAttributes currentObj = this;
        // reverse the order so that the lowest attribute scopes will overwrite the upper ones if there were conflicts
        var stack = new ArrayDeque<IScopedInstrumentationAttributes>();
        while (currentObj != null) {
            stack.addFirst((IScopedInstrumentationAttributes) currentObj);
            currentObj = currentObj.getEnclosingScope();
        }
        var builder = stack.stream()
            .collect(Utils.foldLeft(Attributes.builder(), (b, iia) -> iia.fillAttributesForSpansBelow(b)));
        return fillExtraAttributesForThisSpan(builder);
    }

    default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
        return builder;
    }

    default AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
        return builder;
    }

    default LongCounter getEndOfScopeCountMetric() {
        return getMetrics().contextCounter;
    }

    default DoubleHistogram getEndOfScopeDurationMetric() {
        return getMetrics().contextDuration;
    }

    default void endSpan(IContextTracker contextTracker) {
        var span = getCurrentSpan();
        span.setAllAttributes(getPopulatedSpanAttributes());
        span.end();
        contextTracker.onContextClosed(this);
    }

    default void sendMeterEventsForEnd() {
        meterIncrementEvent(getEndOfScopeCountMetric());
        meterHistogramMillis(getEndOfScopeDurationMetric());
    }

    default void close() {
        endSpan(getContextTracker());
        sendMeterEventsForEnd();
    }

    @Override
    default void addTraceException(Throwable e, boolean isPropagating) {
        IWithStartTimeAndAttributes.super.addTraceException(e, isPropagating);
        final var span = getCurrentSpan();
        if (isPropagating) {
            span.recordException(e, Attributes.of(SemanticAttributes.EXCEPTION_ESCAPED, true));
        } else {
            span.recordException(e);
        }
    }

    @Override
    default void meterIncrementEvent(LongCounter c, long increment, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            IWithStartTimeAndAttributes.super.meterIncrementEvent(c, increment, attributesBuilder);
        }
    }

    @Override
    default void meterDeltaEvent(LongUpDownCounter c, long delta, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            IWithStartTimeAndAttributes.super.meterDeltaEvent(c, delta, attributesBuilder);
        }
    }

    @Override
    default void meterHistogram(DoubleHistogram histogram, double value, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            IWithStartTimeAndAttributes.super.meterHistogram(histogram, value, attributesBuilder);
        }
    }

    @Override
    default void meterHistogram(LongHistogram histogram, long value, AttributesBuilder attributesBuilder) {
        try (var scope = new NullableExemplarScope(getCurrentSpan())) {
            IWithStartTimeAndAttributes.super.meterHistogram(histogram, value, attributesBuilder);
        }
    }

    default void addEvent(String eventName) {
        getCurrentSpan().addEvent(eventName);
    }

    default void setTraceAttribute(AttributeKey<Long> attributeKey, long attributeValue) {
        getCurrentSpan().setAttribute(attributeKey, attributeValue);
    }

    default void setAttribute(AttributeKey<String> attributeKey, String attributeValue) {
        getCurrentSpan().setAttribute(attributeKey, attributeValue);
    }

    default void setAllAttributes(Attributes allAttributes) {
        getCurrentSpan().setAllAttributes(allAttributes);
    }
}
