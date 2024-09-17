package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;

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
