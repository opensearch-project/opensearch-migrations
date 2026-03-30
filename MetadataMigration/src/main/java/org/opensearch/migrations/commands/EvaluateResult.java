package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cli.Transformers;

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
    @Getter
    private final Transformers transformations;

    @Override
    public int getExitCode() {
        int alreadyExistsCount = (items != null) ? items.getAlreadyExistsCount() : 0;
        return Math.max(exitCode, Math.max(collectErrors().size(), alreadyExistsCount));
    }
}
