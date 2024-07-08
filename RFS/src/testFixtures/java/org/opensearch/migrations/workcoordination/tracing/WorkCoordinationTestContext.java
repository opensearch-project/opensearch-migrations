package org.opensearch.migrations.workcoordination.tracing;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import com.rfs.tracing.RootWorkCoordinationContext;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class WorkCoordinationTestContext extends RootWorkCoordinationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public WorkCoordinationTestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
                                       IContextTracker contextTracker)
    {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<WorkCoordinationTestContext> factory() {
        return new TrackingTestContextFactory<>(WorkCoordinationTestContext.class);
    }
}
