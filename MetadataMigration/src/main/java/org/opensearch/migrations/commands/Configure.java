package org.opensearch.migrations.commands;

import org.opensearch.migrations.clusters.SourceCluster;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Configure {
    public Configure source(SourceCluster source) {
        return this;
    }

    public ConfigureResult execute() {
        log.atError().setMessage("configure is not supported").log();
        return new ConfigureResult(9999);
    }
}
