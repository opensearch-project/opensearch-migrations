package com.rfs.version_universal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import com.rfs.common.SnapshotRepo.Provider;
import com.rfs.models.GlobalMetadata;
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
