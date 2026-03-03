package org.opensearch.migrations.bulkload.version_es_7_10;

import org.opensearch.migrations.UnboundVersionMatchers;
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

public class SnapshotReader_ES_7_10 implements ClusterSnapshotReader {
    private Version version;
    
    @Getter
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.equalOrGreaterThanES_7_9
            .or(VersionMatchers.isES_8_X)
            .or(VersionMatchers.isOS_1_X)
            .or(VersionMatchers.isOS_2_X)
            .test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return VersionMatchers.equalOrGreaterThanES_7_9
            .or(UnboundVersionMatchers.isGreaterOrEqualES_7_10)
            .or(UnboundVersionMatchers.anyOS)
            .test(version);
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
    public SnapshotFileFinder getSnapshotFileFinder() {
        return new BaseSnapshotFileFinder();
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new GlobalMetadataFactory_ES_7_10(this.getSnapshotRepo());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new IndexMetadataFactory_ES_7_10(this.getSnapshotRepo());
    }

    @Override
    public ShardMetadata.Factory getShardMetadata() {
        return new ShardMetadataFactory_ES_7_10(this.getSnapshotRepo());
    }


    @Override
    public boolean getSoftDeletesPossible() {
        return ElasticsearchConstants_ES_7_10.SOFT_DELETES_POSSIBLE;
    }

    @Override
    public String getSoftDeletesFieldData() {
        return ElasticsearchConstants_ES_7_10.SOFT_DELETES_FIELD;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Snapshot: %s %s", version, sourceRepo);
    }

    private SnapshotRepo.Provider getSnapshotRepo() {
        if (sourceRepo == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return new SnapshotRepoProvider_ES_7_10(sourceRepo);
    }
}
