package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

public interface IScopedInstrumentationAttributes extends IInstrumentationAttributes {

    Span getCurrentSpan();

    default void endSpan() {
        getCurrentSpan().end();
    }
}
