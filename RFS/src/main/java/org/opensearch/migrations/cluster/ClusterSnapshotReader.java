package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;

import com.rfs.common.SourceRepo;
import com.rfs.models.ShardMetadata;

/** Reads data for a cluster from a snapshot */
public interface ClusterSnapshotReader extends ClusterReader {

    /** Snapshots are read differently based on their versions */
    void initialize(Version version);

    /** Where to read the snapshot from */
    void initialize(SourceRepo sourceRepo);

    /** Reads information about index shards */
    ShardMetadata.Factory getShardMetadata();

    /** buffer size - bytes */
    int getBufferSizeInBytes();

    /** if soft deletes can be in the snapshot */
    boolean getSoftDeletesPossible();

    /** gets the soft deletes can field data */
    String getSoftDeletesFieldData();
}
