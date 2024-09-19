package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class EvaluateResult implements MigrationItemResult {
    private final Clusters clusters;
    private final Items items;
    private final String errorMessage;
    private final int exitCode;
}
