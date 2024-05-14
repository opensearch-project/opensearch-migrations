package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.util.stream.Stream;

public interface IInstrumentConstructor extends IContextTracker {
    @NonNull Span buildSpan(IScopedInstrumentationAttributes forScope, String spanName, Stream<Span> linkedSpans);
}
