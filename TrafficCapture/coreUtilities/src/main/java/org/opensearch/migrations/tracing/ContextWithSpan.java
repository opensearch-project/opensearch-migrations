package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ContextWithSpan<T extends IWithAttributes<?>> {
    public final T context;
    public final Span span;
}
