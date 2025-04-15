package org.opensearch.migrations.cluster;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.IndexCreator;

/** Writes data onto a cluster */
public interface ClusterWriter extends VersionSpecificCluster {

    /** Allow forcing the version */
    ClusterWriter initialize(Version versionOverride);

    /** Filters what data is written onto the cluster */
    ClusterWriter initialize(DataFilterArgs dataFilterArgs);

    /** Creates global metadata items */
    public GlobalMetadataCreator getGlobalMetadataCreator();

    /** Creates indices */
    public IndexCreator getIndexCreator();

    /** Gets the awareness attribute settings of the cluster */
    AwarenessAttributeSettings getAwarenessAttributeSettings();

}
