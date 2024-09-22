package org.opensearch.migrations.workcoordination.tracing;

import org.opensearch.migrations.bulkload.framework.tracing.TrackingTestContextFactory;
import org.opensearch.migrations.bulkload.tracing.RootWorkCoordinationContext;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class WorkCoordinationTestContext extends RootWorkCoordinationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public WorkCoordinationTestContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<WorkCoordinationTestContext> factory() {
        return TrackingTestContextFactory.factoryViaCtor(WorkCoordinationTestContext.class);
    }
}
