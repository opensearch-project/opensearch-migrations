package org.opensearch.migrations.cluster;

import com.rfs.common.http.ConnectionContext;

/** Remote cluster */
public interface RemoteCluster extends VersionSpecificCluster {

    /** Remote clusters are communicated with via a connection */
    void initialize(ConnectionContext connection);
}
