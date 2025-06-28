package org.opensearch.migrations.bulkload.version_es_5_4;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.version_es_6_8.GlobalMetadataFactory_ES_6_8;
import org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataFactory_ES_6_8;
import org.opensearch.migrations.bulkload.version_es_6_8.ShardMetadataFactory_ES_6_8;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

public class SnapshotReader_ES_5_4 implements ClusterSnapshotReader {

    private Version version;
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return UnboundVersionMatchers.isBelowES_5_X
            .or(VersionMatchers.equalOrBetween_ES_5_0_and_5_4)
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
        return false;
    }

    @Override
    public String getSoftDeletesFieldData() {
        return null;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public int getBufferSizeInBytes() {
        return 102400;
    }

    private SnapshotRepo.Provider getSnapshotRepo() {
        if (sourceRepo == null) {
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return new SnapshotRepoProvider_ES_5_4(sourceRepo);
    }

    @Override
    public String toString() {
        // These values could be null, don't want to crash during toString
        return String.format("Snapshot: %s %s", version, sourceRepo);
    }
}
