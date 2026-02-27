package org.opensearch.migrations.bulkload.version_universal;

import org.opensearch.migrations.bulkload.common.SnapshotRepo.Provider;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class RemoteMetadataFactory implements GlobalMetadata.Factory {

    private final RemoteReaderClient client;

    @Override
    public GlobalMetadata fromRepo(String snapshotName) {
        log.info("Ignoring snapshot parameter, getting data from cluster directly");

        return new RemoteMetadata(client.getClusterData());
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        throw new UnsupportedOperationException("Unimplemented method 'fromJsonNode'");
    }

    @Override
    public SmileFactory getSmileFactory() {
        throw new UnsupportedOperationException("Unimplemented method 'getSmileFactory'");
    }

    @Override
    public Provider getRepoDataProvider() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepoDataProvider'");
    }
    
}
