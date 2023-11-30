package org.opensearch.migrations.coreutils;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import java.util.function.Function;

public interface SpanGenerator extends Function<Attributes, Span> { }
