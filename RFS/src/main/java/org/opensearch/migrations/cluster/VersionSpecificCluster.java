package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;

/** Version specific cluster */
public interface VersionSpecificCluster {

    /** Can this version be used with the cluster */
    boolean compatibleWith(Version version);

    /** Gets the detected version of the cluster */
    Version getVersion();
}
