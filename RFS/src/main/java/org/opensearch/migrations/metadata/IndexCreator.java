package org.opensearch.migrations.metadata;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

public interface IndexCreator {
    public CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        AwarenessAttributeSettings awarenessAttributeSettings,
        ICreateIndexContext context
    );

    /** Returns a creator that creates indices in one OpenSearch Serverless collection behind a per-account endpoint. */
    public IndexCreator forCollection(String serverlessCollection);
}
