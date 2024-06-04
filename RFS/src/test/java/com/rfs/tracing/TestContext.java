package com.rfs.tracing;

import lombok.Getter;
import org.opensearch.migrations.tracing.BacktracingContextTracker;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class TestContext extends RootRfsContext {  @Getter
    public InMemoryInstrumentationBundle instrumentationBundle;

    public static TestContext withTracking(boolean tracing, boolean metrics) {
        return new TestContext(new InMemoryInstrumentationBundle(tracing, metrics), new BacktracingContextTracker());
    }

    public static TestContext withAllTracking() {
        return withTracking(true, true);
    }

    public static TestContext noOtelTracking() {
        return new TestContext(new InMemoryInstrumentationBundle(null, null), new BacktracingContextTracker());
    }

    public TestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle, IContextTracker contextTracker) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.instrumentationBundle = inMemoryInstrumentationBundle;
    }

    public IRfsContexts.IRequestContext createUnboundRequestContext() {
        return new RfsContexts.GenericRequestContext(this, null, "testRequest");
    }
}
