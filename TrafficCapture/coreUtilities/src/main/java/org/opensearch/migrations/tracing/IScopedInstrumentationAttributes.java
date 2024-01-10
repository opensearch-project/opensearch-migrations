package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

public interface IScopedInstrumentationAttributes<S extends IInstrumentConstructor>
        extends IWithStartTimeAndAttributes<S>, AutoCloseable {

    String getActivityName();

    @Override
    @NonNull Span getCurrentSpan();
    LongHistogram getEndOfScopeDurationMetric();
    LongCounter getEndOfScopeCountMetric();

    default void endSpan() {
        getCurrentSpan().end();
    }

    default void sendMeterEventsForEnd() {
        meterIncrementEvent(getEndOfScopeCountMetric());
        meterHistogramMicros(getEndOfScopeDurationMetric());
    }

    default void close() {
        endSpan();
        sendMeterEventsForEnd();
    }

    default void addException(Exception e) {
        getCurrentSpan().recordException(e);
    }
}
