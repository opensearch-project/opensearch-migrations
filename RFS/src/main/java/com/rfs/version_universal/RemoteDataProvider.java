package com.rfs.version_universal;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.common.SnapshotRepo.Index;
import com.rfs.common.SnapshotRepo.Provider;
import com.rfs.common.SnapshotRepo.Snapshot;
import com.rfs.common.SourceRepo;
import lombok.AllArgsConstructor;

@AllArgsConstructor
final class RemoteSnapshotDataProvider implements Provider {
    private final ObjectNode indexData;

    @Override
    public List<Snapshot> getSnapshots() {
        throw new UnsupportedOperationException("Unimplemented method 'getSnapshots'");
    }

    @Override
    public List<Index> getIndicesInSnapshot(String snapshotName) {
        var indexes = new ArrayList<Index>();
        indexData.fields().forEachRemaining(index -> {
            indexes.add(new RemoteIndexSnapshotData(index.getKey()));
        });

        return indexes;
    }

    @Override
    public String getSnapshotId(String snapshotName) {
        throw new UnsupportedOperationException("Unimplemented method 'getSnapshotId'");
    }

    @Override
    public String getIndexId(String indexName) {
        throw new UnsupportedOperationException("Unimplemented method 'getIndexId'");
    }

    @Override
    public SourceRepo getRepo() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepo'");
    }
}
