package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;

public interface IInstrumentConstructor {
    Span buildSpan(IInstrumentationAttributes enclosingScope, String scopeName, String spanName);
    Span buildSpanWithoutParent(String scopeName, String spanName);
    SimpleMeteringClosure buildMeter(IInstrumentationAttributes context);
}
