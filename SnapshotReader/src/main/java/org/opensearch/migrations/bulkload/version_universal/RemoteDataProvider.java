package org.opensearch.migrations.bulkload.version_universal;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo.Index;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.Provider;
import org.opensearch.migrations.bulkload.common.SnapshotRepo.Snapshot;
import org.opensearch.migrations.bulkload.common.SourceRepo;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
        indexData.properties().forEach(index -> indexes.add(new RemoteIndexSnapshotData(index.getKey())));

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
