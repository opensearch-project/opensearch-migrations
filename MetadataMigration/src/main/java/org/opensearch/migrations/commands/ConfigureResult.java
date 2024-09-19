package org.opensearch.migrations.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class ConfigureResult implements Result {
    @Getter
    private final int exitCode;

    @Getter
    private final String errorMessage;

    public String asCliOutput() {
        return this.toString();
    }
}
