package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cli.Transformers;

import lombok.Builder;
import lombok.Getter;

@Builder
public class MigrateResult implements MigrationItemResult {
    @Getter
    private final Clusters clusters;
    @Getter
    private final Items items;
    @Getter
    private final String errorMessage;
    private final int exitCode;
    @Getter
    private final Transformers transformations;

    public int getExitCode() {
        return Math.max(exitCode, collectErrors().size());
    }
}
