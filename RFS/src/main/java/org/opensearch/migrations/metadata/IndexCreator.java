package org.opensearch.migrations.metadata;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_universal.IncompatibleReplicaCountException;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

public interface IndexCreator {
    public CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        ICreateIndexContext context
    ) throws IncompatibleReplicaCountException;
}
