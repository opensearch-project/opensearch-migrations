package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

public abstract class AbstractNestedSpanContext<T extends IScopedInstrumentationAttributes>
        implements IScopedInstrumentationAttributes, IWithStartTime, AutoCloseable {
    final T enclosingScope;
    @Getter final Instant startTime;
    @Getter private Span currentSpan;

    public AbstractNestedSpanContext(T enclosingScope) {
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
    }

    @Override
    public IScopedInstrumentationAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() { return enclosingScope; }

    protected void setCurrentSpan(@NonNull ISpanWithParentGenerator spanGenerator) {
        setCurrentSpan(spanGenerator.apply(getPopulatedAttributes(), enclosingScope.getCurrentSpan()));
    }

    protected void setCurrentSpanWithNoParent(@NonNull ISpanWithParentGenerator spanGenerator) {
        assert enclosingScope == null;
        setCurrentSpan(spanGenerator.apply(getPopulatedAttributes(), null));
    }

    protected void setCurrentSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }

    public void close() {
        endSpan();
    }
}
