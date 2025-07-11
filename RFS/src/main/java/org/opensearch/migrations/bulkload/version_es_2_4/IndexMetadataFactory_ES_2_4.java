package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataFactory_ES_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexMetadataFactory_ES_2_4 implements IndexMetadata.Factory {

    private final SnapshotRepoES24 repoProvider;

    public IndexMetadataFactory_ES_2_4(SnapshotRepoES24 repoProvider) {
        this.repoProvider = repoProvider;
    }

    @Override
    public SnapshotRepoES24 getRepoDataProvider() {
        return repoProvider;
    }

    /**
     * Reads a single index's metadata from the snapshot.
     * Detects ES 2.4 binary or Smile/JSON format automatically.
     */
    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        try {
            log.info("Index metadata file for [{}] is a Smile-encoded JSON. Delegating to ES 6.8 reader.", indexName);
            return readWithES68Factory(snapshotName, indexName);

        } catch (Exception e) {
            throw new IllegalStateException("Error reading index metadata for: " + indexName, e);
        }
    }

    /**
     * Reads *all* index metadata objects in the snapshot.
     */
    public List<IndexMetadata> fromSnapshot() {
        List<String> indices = repoProvider.listIndices();
        if (indices == null || indices.isEmpty()) {
            return Collections.emptyList();
        }

        List<IndexMetadata> results = new ArrayList<>();
        for (String indexName : indices) {
            results.add(fromRepo("unknown-snapshot", indexName));
        }
        return results;
    }

    /**
     * Called when Smile format is detected. Delegates to ES 6.8's reader.
     */
    private IndexMetadata readWithES68Factory(String snapshotName, String indexName) {
        var delegateRepo = this.repoProvider.getDelegateRepo();
        var es68Factory = new IndexMetadataFactory_ES_6_8(delegateRepo);
        return es68Factory.fromRepo(snapshotName, indexName);
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        return new IndexMetadataData_ES_2_4(indexName, root);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return null;
    }

    @Override
    public String getIndexFileId(String snapshotId, String indexName) {
        return snapshotId;
    }
}
