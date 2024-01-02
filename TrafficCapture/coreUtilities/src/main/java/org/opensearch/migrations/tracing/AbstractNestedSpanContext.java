package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;

import java.time.Instant;

public abstract class AbstractNestedSpanContext<T extends IInstrumentationAttributes>
        implements IScopedInstrumentationAttributes, IWithStartTime, AutoCloseable {
    final T enclosingScope;
    @Getter final Instant startTime;
    @Getter private Span currentSpan;
    @Getter private final IInstrumentConstructor rootInstrumentationScope;

    protected AbstractNestedSpanContext(T enclosingScope) {
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
        this.rootInstrumentationScope = enclosingScope.getRootInstrumentationScope();
    }

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() { return enclosingScope; }

    protected void initializeSpan() {
        initializeSpan(rootInstrumentationScope.buildSpan(enclosingScope, getScopeName(), getActivityName()));
    }

    public void initializeSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }
}
