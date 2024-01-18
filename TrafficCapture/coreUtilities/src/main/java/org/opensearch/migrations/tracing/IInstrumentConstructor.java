package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

import java.util.stream.Stream;

public interface IInstrumentConstructor {
    @NonNull Span buildSpan(IInstrumentationAttributes forScope, String spanName, Stream<Span> linkedSpans,
                            AttributesBuilder attributesBuilder);

    /**
     * For debugging, this will be overridden to track creation and termination of spans
     */
    default void onContextCreated(IScopedInstrumentationAttributes newScopedContext) {}

    /**
     * For debugging, this will be overridden to track creation and termination of spans
     */
    default void onContextClosed(IScopedInstrumentationAttributes newScopedContext) {}

}
