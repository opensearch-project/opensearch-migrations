package org.opensearch.migrations.tracing;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;

import lombok.Getter;
import lombok.NonNull;

public abstract class BaseSpanContext<S extends IInstrumentConstructor>
    implements
        IScopedInstrumentationAttributes,
        IHasRootInstrumentationScope<S>,
        AutoCloseable {
    @Getter
    protected final S rootInstrumentationScope;
    @Getter
    final long startTimeNano;
    @Getter
    final Instant startTimeInstant;
    @Getter
    Throwable observedExceptionToIncludeInMetrics;
    @Getter
    private Span currentSpan;

    protected BaseSpanContext(S rootScope) {
        this.startTimeNano = System.nanoTime();
        this.startTimeInstant = Instant.now();
        this.rootInstrumentationScope = rootScope;
    }

    protected static <T> AttributesBuilder addAttributeIfPresent(
        AttributesBuilder attributesBuilder,
        AttributeKey<T> key,
        Optional<T> value
    ) {
        return value.map(v -> attributesBuilder.put(key, v)).orElse(attributesBuilder);
    }

    @Override
    public @NonNull IContextTracker getContextTracker() {
        return rootInstrumentationScope;
    }

    protected void initializeSpan(@NonNull IInstrumentConstructor constructor) {
        initializeSpanWithLinkedSpans(constructor, null);
    }

    protected void initializeSpanWithLinkedSpans(
        @NonNull IInstrumentConstructor constructor,
        Stream<Span> linkedSpans
    ) {
        initializeSpan(constructor, rootInstrumentationScope.buildSpan(this, getActivityName(), linkedSpans));
    }

    public void initializeSpan(@NonNull IInstrumentConstructor constructor, @NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
        constructor.onContextCreated(this);
    }

    @Override
    public void addTraceException(Throwable e, boolean isPropagating) {
        IScopedInstrumentationAttributes.super.addTraceException(e, isPropagating);
        observedExceptionToIncludeInMetrics = e;
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
