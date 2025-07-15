package org.opensearch.migrations.bulkload.version_es_2_4;


import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;


public class SnapshotReader_ES_2_4 implements ClusterSnapshotReader {

    private Version version;
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isES_2_X.test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return UnboundVersionMatchers.isBelowES_5_X
            .or(VersionMatchers.isES_2_X)
            .test(version);
    }

    @Override
    public ClusterSnapshotReader initialize(SourceRepo sourceRepo) {
        this.sourceRepo = sourceRepo;
        return this;
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new GlobalMetadataFactory_ES_2_4(getSnapshotRepo());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new IndexMetadataFactory_ES_2_4((SnapshotRepoES24) getSnapshotRepo());
    }

    @Override
    public ShardMetadata.Factory getShardMetadata() {
        throw new UnsupportedOperationException(
            "Reading ShardMetadata for ES 2.4 snapshots is not yet implemented."
        );
    }

    @Override
    public int getBufferSizeInBytes() {
        return ElasticsearchConstants_ES_2_4.BUFFER_SIZE_IN_BYTES;
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
    public ClusterSnapshotReader initialize(Version version) {
        this.version = version;
        return this;
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
