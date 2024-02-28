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
        <S extends IInstrumentConstructor, T extends IScopedInstrumentationAttributes>
        extends BaseSpanContext<S>
{
    final T enclosingScope;

    protected BaseNestedSpanContext(S rootScope, T enclosingScope) {
        super(rootScope);
        this.enclosingScope = enclosingScope;
    }

    @Override
    public IScopedInstrumentationAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() {
        return enclosingScope;
    }

}
