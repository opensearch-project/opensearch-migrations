package org.opensearch.migrations.snapshot.creation.tracing;

import org.opensearch.migrations.bulkload.framework.tracing.TrackingTestContextFactory;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class SnapshotTestContext extends RootSnapshotContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public SnapshotTestContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<SnapshotTestContext> factory() {
        return TrackingTestContextFactory.factoryViaCtor(SnapshotTestContext.class);
    }

    public IRfsContexts.IRequestContext createUnboundRequestContext() {
        return new RfsContexts.GenericRequestContext(this, null, "testRequest");
    }
}
