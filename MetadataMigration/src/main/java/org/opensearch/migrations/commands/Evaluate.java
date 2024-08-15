package org.opensearch.migrations.commands;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Evaluate {

    public EvaluateResult execute() {
        log.atError().setMessage("evaluate is not supported").log();
        return new EvaluateResult(9999);
    }
}
