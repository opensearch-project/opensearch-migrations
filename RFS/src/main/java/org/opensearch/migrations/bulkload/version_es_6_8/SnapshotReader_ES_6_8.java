package org.opensearch.migrations.bulkload.version_es_6_8;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.Getter;

public class SnapshotReader_ES_6_8 implements ClusterSnapshotReader {
    private Version version;
    
    @Getter
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isES_5_X
            .or(VersionMatchers.isES_6_X)
            .or(VersionMatchers.equalOrBetween_ES_7_0_and_7_8)
            .test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return VersionMatchers.isES_5_X
            .or(VersionMatchers.isES_6_X)
            .or(VersionMatchers.equalOrBetween_ES_7_0_and_7_8)
            .test(version);
    }

    @Override
    public ClusterSnapshotReader initialize(SourceRepo sourceRepo) {
        this.sourceRepo = sourceRepo;
        return this;
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new GlobalMetadataFactory_ES_6_8(getSnapshotRepo());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new IndexMetadataFactory_ES_6_8(getSnapshotRepo());
    }

    @Override
    public ShardMetadata.Factory getShardMetadata() {
        return new ShardMetadataFactory_ES_6_8(getSnapshotRepo());
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
    public SnapshotFileFinder getSnapshotFileFinder() {
        return new BaseSnapshotFileFinder();
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public ClusterSnapshotReader initialize(Version version) {
        this.version = version;
        return this;
    }

    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Snapshot: %s %s", version, sourceRepo);
    }

    private SnapshotRepo.Provider getSnapshotRepo() {
        if (sourceRepo == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return new SnapshotRepoProvider_ES_6_8(sourceRepo);
    }

}
