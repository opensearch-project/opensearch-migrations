package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.NonNull;

public interface IInstrumentConstructor {
    @NonNull Span buildSpan(IInstrumentationAttributes enclosingScope, String spanName, Span linkedSpan,
                            AttributesBuilder attributesBuilder);
}
