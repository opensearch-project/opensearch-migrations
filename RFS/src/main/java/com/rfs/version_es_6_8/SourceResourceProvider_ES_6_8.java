package com.rfs.version_es_6_8;

import com.rfs.common.ClusterVersion;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;
import com.rfs.common.SourceResourceProvider;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;

public class SourceResourceProvider_ES_6_8 implements SourceResourceProvider {

    @Override
    public GlobalMetadata.Factory getGlobalMetadataFactory(SnapshotRepo.Provider repoDataProvider) {
        return new GlobalMetadataFactory_ES_6_8(repoDataProvider);
    }

    @Override
    public SnapshotRepo.Provider getSnapshotRepoProvider(SourceRepo sourceRepo) {
        return new SnapshotRepoProvider_ES_6_8(sourceRepo);
    }

    @Override
    public IndexMetadata.Factory getIndexMetadataFactory(SnapshotRepo.Provider repoDataProvider) {
        return new IndexMetadataFactory_ES_6_8(repoDataProvider);
    }

    @Override
    public ShardMetadata.Factory getShardMetadataFactory(SnapshotRepo.Provider repoDataProvider) {
        return new ShardMetadataFactory_ES_6_8(repoDataProvider);
    }

    @Override
    public int getBufferSizeInBytes() {
        return ElasticsearchConstants_ES_6_8.BUFFER_SIZE_IN_BYTES;
    }

    @Override
    public boolean getSoftDeletesPossible() {
        return ElasticsearchConstants_ES_6_8.SOFT_DELETES_POSSIBLE;
    }

    @Override
    public String getSoftDeletesFieldData() {
        return ElasticsearchConstants_ES_6_8.SOFT_DELETES_FIELD;
    }

    @Override
    public ClusterVersion getVersion() {
        return ClusterVersion.ES_6_8;
    }

}
