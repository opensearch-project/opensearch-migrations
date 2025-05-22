package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;

import lombok.Builder;
import lombok.Getter;

@Builder
public class EvaluateResult implements MigrationItemResult {
    @Getter
    private final Clusters clusters;
    @Getter
    private final Items items;
    @Getter
    private final String errorMessage;
    private final int exitCode;

    @Override
    public int getExitCode() {
        return Math.max(exitCode, collectErrors().size());
    }
}
