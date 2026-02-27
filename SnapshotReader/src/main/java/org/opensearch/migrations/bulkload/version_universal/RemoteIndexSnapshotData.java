package org.opensearch.migrations.bulkload.version_universal;


import org.opensearch.migrations.bulkload.common.SnapshotRepo.Index;

import lombok.AllArgsConstructor;

@AllArgsConstructor
final class RemoteIndexSnapshotData implements Index {
    private final String indexName;

    @Override
    public String getName() {
        return indexName;
    }

    @Override
    public String getId() {
        throw new UnsupportedOperationException("Unimplemented method 'getId'");
    }
}
