package org.opensearch.migrations.bulkload.version_es_1_7;

import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class IndexMetadataFactory_ES_1_7 implements IndexMetadata.Factory {

    private final SnapshotRepo.Provider repoDataProvider;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public IndexMetadataFactory_ES_1_7(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public JsonNode getJsonNode(SnapshotRepo.Provider repo, String indexName, String snapshotName, SmileFactory smileFactory) {
        try {
            SnapshotRepoES17 es17Repo = (SnapshotRepoES17) repo;
            // ES 1.7 stores it as snapshot-<snapshotName> under the index dir
            byte[] data = es17Repo.getIndexMetadataFile(indexName, snapshotName);
            return objectMapper.readTree(data);
        } catch (Exception e) {
            throw new RfsException("Could not load index metadata for index: " + indexName, e);
        }
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_1_7(objectNodeRoot, indexName);
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }

    @Override
    public SmileFactory getSmileFactory() {
        // ES 1.7 snapshots are plain JSON
        return null;
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        // ES 1.7 follows indices/blog_legacy_2023/snapshot-my_snap_evaluate
        return "snapshot-" + snapshotName;
    }
}
