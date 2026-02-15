package org.opensearch.migrations.bulkload.version_es_2_4;


import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.Getter;


public class SnapshotReader_ES_2_4 implements ClusterSnapshotReader {
    private Version version;
    
    @Getter
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isES_2_X.test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return VersionMatchers.isES_2_X
            .test(version);
    }

    @Override
    public SnapshotFileFinder getSnapshotFileFinder() {
        return new SnapshotFileFinder_ES_2_4();
    }

    @Override
    public ClusterSnapshotReader initialize(SourceRepo sourceRepo) {
        this.sourceRepo = sourceRepo;
        return this;
    }

    @Override
    public ClusterSnapshotReader initialize(Version version) {
        this.version = version;
        return this;
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new GlobalMetadataFactory_ES_2_4(getSnapshotRepo());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new IndexMetadataFactory_ES_2_4(getSnapshotRepo());
    }

    @Override
    public ShardMetadata.Factory getShardMetadata() {
        return new ShardMetadataFactory_ES_2_4(getSnapshotRepo());
    }


    @Override
    public boolean getSoftDeletesPossible() {
        return ElasticsearchConstants_ES_2_4.SOFT_DELETES_POSSIBLE;
    }

    @Override
    public String getSoftDeletesFieldData() {
        return ElasticsearchConstants_ES_2_4.SOFT_DELETES_FIELD;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Snapshot: %s %s", version, sourceRepo);
    }

    private SnapshotRepo.Provider getSnapshotRepo() {
        if (sourceRepo == null) {
            throw new UnsupportedOperationException("initialize(...) must be called before using getSnapshotRepo()");
        }
        return new SnapshotRepoProvider_ES_2_4(sourceRepo);
    }
}
