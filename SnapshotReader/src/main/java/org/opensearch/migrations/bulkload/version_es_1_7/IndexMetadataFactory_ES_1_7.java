package org.opensearch.migrations.bulkload.version_es_1_7;

import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import static org.opensearch.migrations.bulkload.version_es_1_7.ElasticsearchConstants_ES_1_7.SNAPSHOT_PREFIX;

public class IndexMetadataFactory_ES_1_7 implements IndexMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public IndexMetadataFactory_ES_1_7(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        try {
            SnapshotRepoES17 es17Repo = (SnapshotRepoES17) getRepoDataProvider();
            byte[] data = es17Repo.getIndexMetadataFile(indexName, snapshotName);
            JsonNode root = objectMapper.readTree(data);
            return fromJsonNode(root.get(indexName), indexName, indexName);
        } catch (Exception e) {
            throw new RfsException("Could not load index metadata for index: " + indexName, e);
        }
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        return new IndexMetadataData_ES_1_7((ObjectNode) root, indexName);
    }

    @Override
    public SmileFactory getSmileFactory() {
        // ES 1.7 snapshots are plain JSON
        throw new IllegalArgumentException("ES 1.x does not use Smile encoding for global metadata");
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        // ES 1.7 follows indices/blog_legacy_2023/snapshot-my_snap_evaluate
        return SNAPSHOT_PREFIX + snapshotName;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
