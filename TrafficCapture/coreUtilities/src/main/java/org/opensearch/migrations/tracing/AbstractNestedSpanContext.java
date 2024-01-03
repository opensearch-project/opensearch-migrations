package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;

import java.time.Instant;

public abstract class AbstractNestedSpanContext<T extends IInstrumentationAttributes>
        implements IScopedInstrumentationAttributes, IWithStartTimeAndAttributes, AutoCloseable {
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
        initializeSpan(Attributes.builder());
    }

    protected void initializeSpan(AttributesBuilder attributesBuilder) {
        initializeSpan(rootInstrumentationScope.buildSpan(enclosingScope, getScopeName(), getActivityName(),
                attributesBuilder));
    }

    public void initializeSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }
}
