package org.opensearch.migrations.bulkload.framework;

import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

public interface SimpleRestoreFromSnapshot {

    public static SimpleRestoreFromSnapshot forCluster(final String sourceClusterUrl) {
        // TODO: determine version from source cluster
        return new SimpleRestoreFromSnapshot_ES_7_10();
    }

    public default void fullMigrationViaLocalSnapshot(
        final String targetClusterUrl,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) throws Exception {
        // TODO: Dynamically create / clean these up during tests
        final var tempSnapshotName = "";
        final var compressedSnapshotDirectory = "";
        final var unpackedShardDataDir = Path.of("");
        final var indices = extractSnapshotIndexData(
            compressedSnapshotDirectory,
            tempSnapshotName,
            unpackedShardDataDir
        );
        var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(targetClusterUrl)
                .build()
                .toConnectionContext());
        final var targetClusterClient =clientFactory.determineVersionAndCreate();

        // TODO: This should update the following metdata:
        // - Global cluster state
        // - Index Templates
        // - Indices
        // - Documents

        updateTargetCluster(indices, unpackedShardDataDir, targetClusterClient, context);
    }

    public List<IndexMetadata> extractSnapshotIndexData(
        final String localPath,
        final String snapshotName,
        final Path unpackedShardDataDir
    ) throws Exception;

    public void updateTargetCluster(
        final List<IndexMetadata> indices,
        final Path unpackedShardDataDir,
        final OpenSearchClient client,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) throws Exception;

}
