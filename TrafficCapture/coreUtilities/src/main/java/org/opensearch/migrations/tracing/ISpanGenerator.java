package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import java.util.function.Function;

public interface ISpanGenerator extends Function<Attributes, Span> { }
