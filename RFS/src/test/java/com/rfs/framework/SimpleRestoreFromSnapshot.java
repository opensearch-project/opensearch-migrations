package com.rfs.framework;

import java.nio.file.Path;
import java.util.List;

import com.rfs.common.ConnectionDetails;
import com.rfs.common.IndexMetadata;
import com.rfs.common.OpenSearchClient;

public interface SimpleRestoreFromSnapshot {

    public static SimpleRestoreFromSnapshot forCluster(final String sourceClusterUrl) {
        // TODO: determine version from source cluster
        return new SimpleRestoreFromSnapshot_ES_7_10();
    }

    public default void fullMigrationViaLocalSnapshot(final String snapshotName, final String targetClusterUrl) throws Exception {
        // TODO: Dynamically create / clean these up during tests
        final var compressedSnapshotDirectory = "";
        final var unpackedShardDataDir = Path.of("");
        final var indices = extractSnapshotIndexData(compressedSnapshotDirectory, snapshotName, unpackedShardDataDir);

        final var targetClusterClient = new OpenSearchClient(new ConnectionDetails(targetClusterUrl, null, null)); 

        // TODO: This should update the following metdata:
        //    - Global cluster state
        //    - Index Templates
        //    - Indices
        //    - Documents

        updateTargetCluster(indices, unpackedShardDataDir, targetClusterClient);
    }

    public List<IndexMetadata.Data> extractSnapshotIndexData(final String localPath, final String snapshotName, final Path unpackedShardDataDir) throws Exception;

    public void updateTargetCluster(final List<IndexMetadata.Data> indices, final Path unpackedShardDataDir, final OpenSearchClient client) throws Exception;

}
