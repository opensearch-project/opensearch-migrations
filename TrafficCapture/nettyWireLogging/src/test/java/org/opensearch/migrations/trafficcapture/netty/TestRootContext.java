package org.opensearch.migrations.trafficcapture.netty;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;

import lombok.Getter;
import lombok.NonNull;

public class TestRootContext extends RootWireLoggingContext implements AutoCloseable {
    @Getter
    InMemoryInstrumentationBundle instrumentationBundle;

    public TestRootContext() {
        this(false, false);
    }

    public TestRootContext(boolean trackMetrics, boolean trackTraces) {
        this(trackMetrics, trackTraces, DO_NOTHING_TRACKER);
    }

    public TestRootContext(boolean trackMetrics, boolean trackTraces, @NonNull IContextTracker contextTracker) {
        this(new InMemoryInstrumentationBundle(trackMetrics, trackTraces), contextTracker);
    }

    public TestRootContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.instrumentationBundle = inMemoryInstrumentationBundle;
    }

    @Override
    public void close() {
        instrumentationBundle.close();
    }
}
