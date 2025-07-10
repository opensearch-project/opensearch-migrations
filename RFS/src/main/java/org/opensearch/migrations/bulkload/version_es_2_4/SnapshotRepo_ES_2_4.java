package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;

/**
 * ES 2.4-specific extension of SnapshotRepo.Provider
 * Exposes listing of indices and reading their metadata files
 */
public interface SnapshotRepo_ES_2_4 extends SnapshotRepo.Provider {
    List<String> listIndices();
    byte[] getIndexMetadataFile(String indexName);
}
