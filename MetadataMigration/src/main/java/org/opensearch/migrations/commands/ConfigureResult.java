package org.opensearch.migrations.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class ConfigureResult implements Result {
    @Getter
    private final int exitCode;
}
