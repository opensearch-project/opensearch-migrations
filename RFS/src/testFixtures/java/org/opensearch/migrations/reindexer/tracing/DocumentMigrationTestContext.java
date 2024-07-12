package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.rfs.framework.tracing.TrackingTestContextFactory;
import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;

public class DocumentMigrationTestContext extends RootDocumentMigrationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    @Override
    public WorkCoordinationTestContext getWorkCoordinationContext() {
        return (WorkCoordinationTestContext) super.getWorkCoordinationContext();
    }

    public DocumentMigrationTestContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker,
        WorkCoordinationTestContext workCoordinationContext
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker, workCoordinationContext);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }

    public static TrackingTestContextFactory<DocumentMigrationTestContext> factory(
        WorkCoordinationTestContext rootWorkCoordinationContext
    ) {
        return new TrackingTestContextFactory<>(
            (a, b) -> new DocumentMigrationTestContext(a, b, rootWorkCoordinationContext)
        );
    }

    public IRfsContexts.IRequestContext createUnboundRequestContext() {
        return new RfsContexts.GenericRequestContext(this, null, "testRequest");
    }
}
