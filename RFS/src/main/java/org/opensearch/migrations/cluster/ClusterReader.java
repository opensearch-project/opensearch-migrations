package org.opensearch.migrations.cluster;

import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;

/** Reads data from a cluster */
public interface ClusterReader extends VersionSpecificCluster {

    /** Reads the global metadata of the cluster */
    GlobalMetadata.Factory getGlobalMetadata();

    /** Reads the index metadata of the cluster */
    IndexMetadata.Factory getIndexMetadata();
}
