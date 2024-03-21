package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.SemanticAttributes;
import lombok.NonNull;
import org.opensearch.migrations.Utils;

import java.util.ArrayDeque;

public interface IScopedInstrumentationAttributes
        extends IWithStartTimeAndAttributes, AutoCloseable {

    String getActivityName();

    @Override
    IScopedInstrumentationAttributes getEnclosingScope();

    @Override
    CommonScopedMetricInstruments getMetrics();

    @NonNull Span getCurrentSpan();

    default Attributes getPopulatedSpanAttributes() {
        return getPopulatedSpanAttributesBuilder().build();
    }

    default AttributesBuilder getPopulatedSpanAttributesBuilder() {
        IInstrumentationAttributes currentObj = this;
        // reverse the order so that the lowest attribute scopes will overwrite the upper ones if there were conflicts
        var stack = new ArrayDeque<IScopedInstrumentationAttributes>();
        while (currentObj != null) {
            if (currentObj instanceof IScopedInstrumentationAttributes) {
                stack.addFirst((IScopedInstrumentationAttributes) currentObj);
            }
            currentObj = currentObj.getEnclosingScope();
        }
        var builder = stack.stream()
                .collect(Utils.foldLeft(Attributes.builder(), (b, iia)->iia.fillAttributesForSpansBelow(b)));
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

    default void endSpan() {
        var span = getCurrentSpan();
        span.setAllAttributes(getPopulatedSpanAttributes());
        span.end();
    }

    default void sendMeterEventsForEnd() {
        meterIncrementEvent(getEndOfScopeCountMetric());
        meterHistogramMillis(getEndOfScopeDurationMetric());
    }

    default void close() {
        endSpan();
        sendMeterEventsForEnd();
    }

    @Override
    default void addException(Throwable e, boolean isPropagating) {
        IWithStartTimeAndAttributes.super.addException(e, isPropagating);
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
}
