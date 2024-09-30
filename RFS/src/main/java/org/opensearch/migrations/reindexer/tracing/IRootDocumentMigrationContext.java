package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.bulkload.tracing.RootWorkCoordinationContext;

public interface IRootDocumentMigrationContext {
    RootWorkCoordinationContext getWorkCoordinationContext();

    IDocumentMigrationContexts.IShardSetupAttemptContext createDocsMigrationSetupContext();

    IDocumentMigrationContexts.IDocumentReindexContext createReindexContext();
}
