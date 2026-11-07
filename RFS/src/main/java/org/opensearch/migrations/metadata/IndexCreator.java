package org.opensearch.migrations.metadata;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

public interface IndexCreator {
    /**
     * @param serverlessCollection the OpenSearch Serverless collection behind a per-account
     *                             endpoint to create the index in, null when the target is not
     *                             collection-routed
     */
    public CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        AwarenessAttributeSettings awarenessAttributeSettings,
        ICreateIndexContext context,
        String serverlessCollection
    );
}
