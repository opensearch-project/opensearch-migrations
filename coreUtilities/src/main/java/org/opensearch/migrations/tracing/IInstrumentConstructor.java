package org.opensearch.migrations.tracing;

import java.util.stream.Stream;

import io.opentelemetry.api.trace.Span;

import lombok.NonNull;

public interface IInstrumentConstructor extends IContextTracker {
    @NonNull
    Span buildSpan(IScopedInstrumentationAttributes forScope, String spanName, Stream<Span> linkedSpans);
}
