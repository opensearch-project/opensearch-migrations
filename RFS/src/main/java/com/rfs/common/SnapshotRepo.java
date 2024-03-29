package com.rfs.common;

import java.util.List;

public class SnapshotRepo {

    /**
     * Defines the behavior required to surface a snapshot repo's metadata
     */
    public static interface Provider {    
        // Returns a list of all snapshots in the snapshot repository
        public List<SnapshotRepo.Snapshot> getSnapshots();
    
        // Returns a list of all indices in the specified snapshot
        public List<SnapshotRepo.Index> getIndicesInSnapshot(String snapshotName);
        
        // Get the ID of a snapshot from its name
        public String getSnapshotId(String snapshotName);
    
        // Get the ID of an index from its name
        public String getIndexId(String indexName);
        
    }

    /**
     * Defines the behavior required to surface the details of a snapshot in a snapshot repo's metadata
     */
    public static interface Snapshot {
        String getName();
        String getId();
    }

    /**
     * Defines the behavior required to surface the details of a index in a snapshot repo's metadata
     */
    public static interface Index {
        String getName();
        String getId();
        List<String> getSnapshots();
    }

}


