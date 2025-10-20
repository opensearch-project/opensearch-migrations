package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public class NullableExemplarScope implements Scope {
    final Scope underlyingScope;

    @SuppressWarnings("MustBeClosedChecker")
    public NullableExemplarScope(Span span) {
        underlyingScope = span == null ? null : Context.current().with(span).makeCurrent();
    }

    @Override
    public void close() {
        if (underlyingScope != null) {
            underlyingScope.close();
        }
    }
}
