package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

public interface IScopedInstrumentationAttributes extends IInstrumentationAttributes, AutoCloseable {

    @Override
    @NonNull Span getCurrentSpan();

    default void endSpan() {
        getCurrentSpan().end();
    }

    default void close() {
        endSpan();
    }
}
