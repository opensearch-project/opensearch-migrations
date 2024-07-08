package org.opensearch.migrations.snapshot.creation.tracing;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class SnapshotTestContext extends RootSnapshotContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public SnapshotTestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
                                        IContextTracker contextTracker) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<SnapshotTestContext> factory() {
        return new TrackingTestContextFactory<>(SnapshotTestContext.class);
    }

    public IRfsContexts.IRequestContext createUnboundRequestContext() {
        return new RfsContexts.GenericRequestContext(this, null, "testRequest");
    }
}
