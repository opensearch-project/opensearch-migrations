package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;

import java.util.Optional;
import java.util.stream.Stream;

public abstract class BaseNestedSpanContext
        <S extends IInstrumentConstructor, T extends IInstrumentationAttributes>
        implements IScopedInstrumentationAttributes, IWithStartTimeAndAttributes, IHasRootInstrumentationScope<S>, AutoCloseable {
    final T enclosingScope;
    @Getter
    final long startNanoTime;
    @Getter
    private Span currentSpan;
    @Getter
    private final S rootInstrumentationScope;
    @Getter
    Throwable observedExceptionToIncludeInMetrics;

    protected static <T> AttributesBuilder addAttributeIfPresent(AttributesBuilder attributesBuilder,
                                                                 AttributeKey<T> key, Optional<T> value) {
        return value.map(v -> attributesBuilder.put(key, v)).orElse(attributesBuilder);
    }

    protected BaseNestedSpanContext(S rootScope, T enclosingScope) {
        rootScope.onContextCreated(this);
        this.enclosingScope = enclosingScope;
        this.startNanoTime = System.nanoTime();
        this.rootInstrumentationScope = rootScope;
    }

    @Override
    public void endSpan() {
        IScopedInstrumentationAttributes.super.endSpan();
        rootInstrumentationScope.onContextClosed(this);
    }

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() {
        return enclosingScope;
    }

    protected void initializeSpan() {
        initializeSpan(Attributes.builder());
    }

    protected void initializeSpan(AttributesBuilder attributesBuilder) {
        initializeSpan(null, attributesBuilder);
    }

    protected void initializeSpan(Stream<Span> linkedSpans, AttributesBuilder attributesBuilder) {
        initializeSpan(rootInstrumentationScope.buildSpan(this, getActivityName(),
                linkedSpans, attributesBuilder));
    }

    public void initializeSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }

    @Override
    public void addException(Throwable e) {
        IScopedInstrumentationAttributes.super.addException(e);
        observedExceptionToIncludeInMetrics = e;
    }
}
