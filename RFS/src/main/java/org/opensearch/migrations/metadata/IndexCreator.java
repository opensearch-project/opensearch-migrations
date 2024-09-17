package org.opensearch.migrations.metadata;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.rfs.models.IndexMetadata;

public interface IndexCreator {
    public boolean create(
        IndexMetadata index,
        MigrationMode mode,
        ICreateIndexContext context
    );
}
