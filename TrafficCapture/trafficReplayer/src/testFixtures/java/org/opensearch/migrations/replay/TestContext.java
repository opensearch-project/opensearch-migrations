package org.opensearch.migrations.replay;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;

public class TestContext implements IScopedInstrumentationAttributes {
    public static final TestContext singleton = new TestContext();

    @Override
    public IInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    @Getter
    public Span currentSpan;
    public TestContext() {
        currentSpan = new SimpleMeteringClosure("test").makeSpanContinuation("testSpan")
                .apply(getPopulatedAttributes(), null);
    }
}
