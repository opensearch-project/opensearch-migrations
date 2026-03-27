package org.opensearch.migrations.cluster;

import org.opensearch.migrations.Version;

/** Version specific cluster */
public interface VersionSpecificCluster {

    /** Can this version be used with the cluster */
    boolean compatibleWith(Version version);

    /** Can this version be used with the cluster after user selects loose compatibility */
    boolean looseCompatibleWith(Version version);

    /** Gets the detected version of the cluster */
    Version getVersion();
}
