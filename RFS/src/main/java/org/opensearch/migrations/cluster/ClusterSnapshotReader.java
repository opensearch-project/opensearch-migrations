package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

/** Reads data for a cluster from a snapshot */
public interface ClusterSnapshotReader extends ClusterReader {

    /** Snapshots are read differently based on their versions */
    ClusterSnapshotReader initialize(Version version);

    /** Where to read the snapshot from */
    ClusterSnapshotReader initialize(SourceRepo sourceRepo);

    /** Reads information about index shards */
    ShardMetadata.Factory getShardMetadata();

    /** if soft deletes can be in the snapshot */
    boolean getSoftDeletesPossible();

    /** gets the soft deletes can field data */
    String getSoftDeletesFieldData();

    /** Get the source repo for the snapshot */
    SourceRepo getSourceRepo();

     /** Returns the appropriate SnapshotFileFinder implementation for version specific SnapshotReader */
    SnapshotFileFinder getSnapshotFileFinder();

    @Override
    default String getFriendlyTypeName() {
        return "Snapshot";
    }
}
