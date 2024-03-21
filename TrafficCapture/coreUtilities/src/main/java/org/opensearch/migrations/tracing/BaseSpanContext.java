package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;

import java.util.Optional;
import java.util.stream.Stream;

public abstract class BaseSpanContext<S extends IInstrumentConstructor>
        implements IScopedInstrumentationAttributes, IWithStartTimeAndAttributes, IHasRootInstrumentationScope<S>, AutoCloseable {
    @Getter
    protected final S rootInstrumentationScope;
    @Getter
    final long startNanoTime;
    @Getter
    Throwable observedExceptionToIncludeInMetrics;
    @Getter
    private Span currentSpan;

    public BaseSpanContext(S rootScope) {
        this.startNanoTime = System.nanoTime();
        this.rootInstrumentationScope = rootScope;
        rootScope.onContextCreated(this);
    }

    protected static <T> AttributesBuilder addAttributeIfPresent(AttributesBuilder attributesBuilder,
                                                                 AttributeKey<T> key, Optional<T> value) {
        return value.map(v -> attributesBuilder.put(key, v)).orElse(attributesBuilder);
    }

    @Override
    public void endSpan() {
        IScopedInstrumentationAttributes.super.endSpan();
        rootInstrumentationScope.onContextClosed(this);
    }

    protected void initializeSpan() {
        initializeSpanWithLinkedSpans(null);
    }

    protected void initializeSpanWithLinkedSpans(Stream<Span> linkedSpans) {
        initializeSpan(rootInstrumentationScope.buildSpan(this, getActivityName(), linkedSpans));
    }

    public void initializeSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }

    @Override
    public void addException(Throwable e, boolean isPropagating) {
        IScopedInstrumentationAttributes.super.addException(e, isPropagating);
        observedExceptionToIncludeInMetrics = e;
    }

    public long getStartNanoTime() {
        return this.startNanoTime;
    }

    public @NonNull Span getCurrentSpan() {
        return this.currentSpan;
    }

    public S getRootInstrumentationScope() {
        return this.rootInstrumentationScope;
    }

    public Throwable getObservedExceptionToIncludeInMetrics() {
        return this.observedExceptionToIncludeInMetrics;
    }
}
