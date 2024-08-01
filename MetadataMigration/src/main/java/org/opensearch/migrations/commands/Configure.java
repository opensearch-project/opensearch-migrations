package org.opensearch.migrations.commands;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Configure {

    public ConfigureResult execute() {
        log.atError().setMessage("configure is not supported").log();
        return new ConfigureResult(9999);
    }
}
