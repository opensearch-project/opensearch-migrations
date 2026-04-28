package org.opensearch.migrations.bulkload.version_es_9_0;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.SnapshotCapabilities;

import lombok.Getter;

public class SnapshotReader_ES_9_0 implements ClusterSnapshotReader {
    private Version version;
    
    @Getter
    private SourceRepo sourceRepo;

    @Override
    public boolean compatibleWith(Version version) {
        return VersionMatchers.isES_9_X
            .or(VersionMatchers.isOS_3_X)
            .test(version);
    }

    @Override
    public boolean looseCompatibleWith(Version version) {
        return UnboundVersionMatchers.isGreaterOrEqualES_9_X
            .or(UnboundVersionMatchers.isGreaterOrEqualOS_3_x)
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
        return new SnapshotFileFinder_ES_9_0(sourceRepo);
    }

    @Override
    public GlobalMetadata.Factory getGlobalMetadata() {
        return new GlobalMetadataFactory_ES_9_0(this.getSnapshotRepo());
    }

    @Override
    public IndexMetadata.Factory getIndexMetadata() {
        return new IndexMetadataFactory_ES_9_0(this.getSnapshotRepo());
    }

    @Override
    public ShardMetadata.Factory getShardMetadata() {
        return new ShardMetadataFactory_ES_9_0(this.getSnapshotRepo());
    }


    @Override
    public SnapshotCapabilities getCapabilities() {
        return new SnapshotCapabilities(
            SnapshotCapabilities.LuceneVersion.LUCENE_10,
            new SnapshotCapabilities.SoftDeleteSupport.Supported(ElasticsearchConstants_ES_9_0.SOFT_DELETES_FIELD)
        );
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
            throw new UnsupportedOperationException("initialize(...) must be called");
        }
        return new SnapshotRepoProvider_ES_9_0(sourceRepo);
    }
}
