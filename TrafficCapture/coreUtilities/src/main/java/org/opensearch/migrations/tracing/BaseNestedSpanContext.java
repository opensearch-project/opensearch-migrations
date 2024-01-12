package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;

import java.time.Instant;

public abstract class BaseNestedSpanContext
        <S extends IInstrumentConstructor, T extends IInstrumentationAttributes>
        implements IScopedInstrumentationAttributes, IWithStartTimeAndAttributes, IHasRootInstrumentationScope<S>, AutoCloseable {
    final T enclosingScope;
    @Getter final Instant startTime;
    @Getter private Span currentSpan;
    @Getter private final S rootInstrumentationScope;

    protected BaseNestedSpanContext(S rootScope, T enclosingScope) {
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
        this.rootInstrumentationScope = rootScope;
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
        initializeSpan(null, attributesBuilder);
    }

    protected void initializeSpan(Span linkedSpan, AttributesBuilder attributesBuilder) {
        initializeSpan(rootInstrumentationScope.buildSpan(enclosingScope, getActivityName(),
                linkedSpan, attributesBuilder));
    }

    public void initializeSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }
}
