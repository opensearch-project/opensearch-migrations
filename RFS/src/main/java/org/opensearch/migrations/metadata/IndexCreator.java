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

    /**
     * Creates the index in an OpenSearch Serverless collection behind a per-account endpoint.
     * serverlessCollection is null when the target is not collection-routed.
     */
    default CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        AwarenessAttributeSettings awarenessAttributeSettings,
        ICreateIndexContext context,
        String serverlessCollection
    ) {
        if (serverlessCollection != null) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support collection routing");
        }
        return create(index, mode, awarenessAttributeSettings, context);
    }
}
