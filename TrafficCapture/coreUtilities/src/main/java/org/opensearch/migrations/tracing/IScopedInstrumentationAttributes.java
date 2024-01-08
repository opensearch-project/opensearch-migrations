package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

public interface IScopedInstrumentationAttributes extends IWithStartTimeAndAttributes, AutoCloseable {

    String getActivityName();

    @Override
    @NonNull Span getCurrentSpan();

    default void endSpan() {
        getCurrentSpan().end();
    }

    default String getEndOfScopeMetricName() {
        return getActivityName() + "Count";
    }
    default String getEndOfScopeDurationMetricName() {
        return getActivityName() + "Duration";
    }

    default void sendMeterEventsForEnd() {
        meterIncrementEvent(getEndOfScopeMetricName());
        meterHistogramMicros(getEndOfScopeDurationMetricName());
    }

    default void close() {
        endSpan();
        sendMeterEventsForEnd();
    }

    default void addException(Exception e) {
        getCurrentSpan().recordException(e);
    }
}
