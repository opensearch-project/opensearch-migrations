package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.RootWorkCoordinationContext;

public interface IRootDocumentMigrationContext {
    RootWorkCoordinationContext getWorkCoordinationContext();

    IDocumentMigrationContexts.IShardSetupAttemptContext createDocsMigrationSetupContext();

    IDocumentMigrationContexts.IDocumentReindexContext createReindexContext();
}
