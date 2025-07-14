package org.opensearch.migrations.cluster;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

/** Reads data from a cluster */
public interface ClusterReader extends VersionSpecificCluster {

    /** Reads the global metadata of the cluster */
    GlobalMetadata.Factory getGlobalMetadata();

    /** Reads the index metadata of the cluster */
    IndexMetadata.Factory getIndexMetadata();

    /** Get the type of reader that is user facing */
    String getFriendlyTypeName();
}
