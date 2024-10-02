package org.opensearch.migrations.bulkload.version_universal;

import org.opensearch.migrations.bulkload.common.SnapshotRepo.Provider;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class RemoteIndexMetadataFactory implements IndexMetadata.Factory {

    private final RemoteReaderClient client;

    private ObjectNode indexData;

    ObjectNode getIndexData() {
        if (indexData == null) {
            indexData = client.getIndexes();
        }
        return indexData;
    }

    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        log.info("Using remote cluster directly");
        return new RemoteIndexMetadata(indexName, (ObjectNode)getIndexData().get(indexName));
    }

    @Override
    public Provider getRepoDataProvider() {
        return new RemoteSnapshotDataProvider(getIndexData());
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        throw new UnsupportedOperationException("Unimplemented method 'fromJsonNode'");
    }

    @Override
    public SmileFactory getSmileFactory() {
        throw new UnsupportedOperationException("Unimplemented method 'getSmileFactory'");
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        throw new UnsupportedOperationException("Unimplemented method 'getIndexFileId'");
    }
}
