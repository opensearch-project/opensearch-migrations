package com.rfs.version_universal;

import java.util.List;

import com.rfs.common.SnapshotRepo.Index;
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

    @Override
    public List<String> getSnapshots() {
        throw new UnsupportedOperationException("Unimplemented method 'getSnapshots'");
    }
}
