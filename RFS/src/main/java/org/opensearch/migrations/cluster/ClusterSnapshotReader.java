package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

/** Reads data for a cluster from a snapshot */
public abstract class ClusterSnapshotReader implements ClusterReader {

    protected Version version;
    protected SourceRepo sourceRepo;
    protected String snapshotName;

    /** Where to read the snapshot from */
    ClusterSnapshotReader initialize(Version version, SourceRepo sourceRepo, String snapshotName) {
        this.version = version;
        this.sourceRepo = sourceRepo;
        this.snapshotName = snapshotName;
        return this;
    }

    /** Reads information about index shards */
    public abstract ShardMetadata.Factory getShardMetadata();

    /** buffer size - bytes */
    public abstract int getBufferSizeInBytes();

    /** if soft deletes can be in the snapshot */
    public abstract boolean getSoftDeletesPossible();

    /** gets the soft deletes can field data */
    public abstract String getSoftDeletesFieldData();
}
