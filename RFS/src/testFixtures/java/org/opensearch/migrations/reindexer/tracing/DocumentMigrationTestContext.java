package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.bulkload.framework.tracing.TrackingTestContextFactory;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class DocumentMigrationTestContext extends RootDocumentMigrationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public DocumentMigrationTestContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<DocumentMigrationTestContext> factory() {
        return new TrackingTestContextFactory<>(DocumentMigrationTestContext::new);
    }

    public IRfsContexts.IRequestContext createUnboundRequestContext() {
        return new RfsContexts.GenericRequestContext(this, null, "testRequest");
    }
}
