package org.opensearch.migrations.cluster;

import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.metadata.GlobalMetadataCreator;
import org.opensearch.migrations.metadata.IndexCreator;

/** Writes data onto a cluster */
public interface ClusterWriter extends VersionSpecificCluster {

    /** Filters what data is written onto the cluster */
    public void initialize(DataFilterArgs dataFilterArgs);

    /** Creates global metadata items */
    public GlobalMetadataCreator getGlobalMetadataCreator();

    /** Creates indices */
    public IndexCreator getIndexCreator();
}
