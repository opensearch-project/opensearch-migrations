package com.rfs.version_es_7_10;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.models.ShardMetadata;

public class SnapshotReader_ES_7_10 implements ClusterSnapshotReader {

    private Version version;
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.equalOrGreaterThanES_7_10.test(version);
    }

    @Override
    public void initialize(SourceRepo sourceRepo) {
        this.sourceRepo = sourceRepo;
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
    public int getBufferSizeInBytes() {
        return ElasticsearchConstants_ES_7_10.BUFFER_SIZE_IN_BYTES;
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

    @Override
    public void initialize(Version version) {
        this.version = version;
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
