package com.rfs.common;

import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;

public interface SourceResourceProvider {
    SnapshotRepo.Provider getSnapshotRepoProvider(SourceRepo sourceRepo);
    IndexMetadata.Factory getIndexMetadataFactory(SnapshotRepo.Provider repoDataProvider);
    ShardMetadata.Factory getShardMetadataFactory(SnapshotRepo.Provider repoDataProvider);

    int getBufferSizeInBytes();
    boolean getSoftDeletesPossible();
    String getSoftDeletesFieldData();
}
