package org.opensearch.migrations.cluster;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

/** Remote cluster */
public interface RemoteCluster extends VersionSpecificCluster {

    /** Remote clusters are communicated with via a connection */
    RemoteCluster initialize(ConnectionContext connection);

    ConnectionContext getConnection();
}
