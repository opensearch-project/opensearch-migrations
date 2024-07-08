package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.IWorkCoordinationContexts;
import com.rfs.tracing.RootWorkCoordinationContext;

public interface IRootDocumentMigrationContext {
    RootWorkCoordinationContext getWorkCoordinationContext();
    IDocumentMigrationContexts.IShardSetupContext createDocsMigrationSetupContext();
    IDocumentMigrationContexts.IDocumentReindexContext createReindexContext();
}
