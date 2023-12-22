package org.opensearch.migrations.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;

public class TestContext implements IScopedInstrumentationAttributes {
    public static final TestContext singleton = new TestContext();

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    @Getter public IInstrumentConstructor rootInstrumentationScope = new RootOtelContext();

    @Getter
    public Span currentSpan;
    public TestContext() {
        currentSpan = new RootOtelContext().buildSpanWithoutParent("testScope", "testSpan");
    }
}
