package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import java.util.function.BiFunction;

public interface ISpanWithParentGenerator extends BiFunction<Attributes, Span,Span> {
}
