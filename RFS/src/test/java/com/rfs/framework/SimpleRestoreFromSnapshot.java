package com.rfs.framework;

import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.models.IndexMetadata;

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
        final var targetClusterClient = new OpenSearchClient(ConnectionContextTestParams.builder()
            .host(targetClusterUrl)
            .build()
            .toConnectionContext(), 4);

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
