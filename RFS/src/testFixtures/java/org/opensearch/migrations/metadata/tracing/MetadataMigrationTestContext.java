package org.opensearch.migrations.metadata.tracing;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class MetadataMigrationTestContext extends RootMetadataMigrationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public static TrackingTestContextFactory<MetadataMigrationTestContext> factory() {
        return new TrackingTestContextFactory<>(MetadataMigrationTestContext.class);
    }

    public MetadataMigrationTestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
                                        IContextTracker contextTracker) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }
}
