package org.opensearch.migrations.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class EvaluateResult implements Result {
    @Getter
    private final int exitCode;
}
