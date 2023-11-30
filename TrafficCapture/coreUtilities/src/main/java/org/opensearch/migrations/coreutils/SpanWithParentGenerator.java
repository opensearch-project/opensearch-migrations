package org.opensearch.migrations.coreutils;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import java.util.function.BiFunction;

public interface SpanWithParentGenerator extends BiFunction<Attributes, Span,Span> {
}
