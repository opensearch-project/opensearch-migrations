package org.opensearch.migrations.commands;

import org.opensearch.migrations.clusters.SourceCluster;

public class Configure {
    public ConfigureResult execute() {
        return new ConfigureResult();
    }

    public Configure source(SourceCluster source) {
        return this;
    }
}
