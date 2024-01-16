package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.util.stream.Stream;

public interface IInstrumentConstructor {
    @NonNull Span buildSpan(IInstrumentationAttributes forScope, String spanName, Stream<Span> linkedSpans,
                            AttributesBuilder attributesBuilder);
}
