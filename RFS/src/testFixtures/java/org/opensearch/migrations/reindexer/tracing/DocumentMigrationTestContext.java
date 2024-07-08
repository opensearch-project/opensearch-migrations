package org.opensearch.migrations.reindexer.tracing;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import com.rfs.tracing.RootWorkCoordinationContext;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class DocumentMigrationTestContext extends RootDocumentMigrationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public DocumentMigrationTestContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
                                        IContextTracker contextTracker,
                                        RootWorkCoordinationContext workCoordinationContext) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker, workCoordinationContext);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<DocumentMigrationTestContext> factory() {
        return new TrackingTestContextFactory<>(DocumentMigrationTestContext.class);
    }
}
