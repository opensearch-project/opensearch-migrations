package org.opensearch.migrations.commands;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Configure {

    public ConfigureResult execute() {
        var message = "configure is not supported";
        log.atError().setMessage(message).log();
        return new ConfigureResult(9999, message);
    }
}
