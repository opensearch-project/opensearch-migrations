package com.rfs.framework.tracing;

import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;
import com.rfs.tracing.RootRfsContext;
import lombok.Getter;
import org.opensearch.migrations.tracing.BacktracingContextTracker;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.LoggingContextTracer;

public class TestContext extends RootRfsContext {
    @Getter
    public InMemoryInstrumentationBundle instrumentationBundle;

    public static TestContext withTracking(boolean tracing, boolean metrics, boolean consoleLogging) {
        IContextTracker tracker = new BacktracingContextTracker();
        if (consoleLogging) {
            tracker = new CompositeContextTracker(tracker, new LoggingContextTracer());
        }

        return new TestContext(new InMemoryInstrumentationBundle(tracing, metrics), tracker);
    }

    public static TestContext withAllTracking() {
        return withTracking(true, true, true);
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
