package org.opensearch.migrations.bulkload.version_es_1_7;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;

/**
 * ES 1.7-specific extension of SnapshotRepo.Provider
 * Exposes listing of indices and reading their metadata files
 */
public interface SnapshotRepoES17 extends SnapshotRepo.Provider {
    byte[] getIndexMetadataFile(String indexName, String snapshotName);
    Path getGlobalMetadataFile(String snapshotName);
    Path getShardMetadataFilePath(String snapshotId, String indexId, int shardId);
}
