package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;

public class EmptyContext implements IInstrumentationAttributes {
    public static final EmptyContext singleton = new EmptyContext();

    private EmptyContext() {}

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder; // nothing more to do
    }
}
