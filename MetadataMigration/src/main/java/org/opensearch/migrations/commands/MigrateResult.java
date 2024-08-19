package org.opensearch.migrations.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class MigrateResult implements Result {
    @Getter
    private final int exitCode;
}
