package org.opensearch.migrations.commands;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import com.beust.jcommander.ParameterException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Migrate extends MigratorEvaluatorBase {

    public Migrate(MigrateOrEvaluateArgs arguments) {
        super(arguments);
    }

    public MigrateResult execute(RootMetadataMigrationContext context) {
        var migrationMode = MigrationMode.PERFORM;
        var evaluateResult = MigrateResult.builder();

        try {
            log.info("Running Metadata Evaluation");

            var clusters = createClusters();
            evaluateResult.clusters(clusters);

            var transformer = selectTransformer(clusters);

            var items = migrateAllItems(migrationMode, clusters, transformer, context);
            evaluateResult.items(items);
        } catch (ParameterException pe) {
            log.atError().setMessage("Invalid parameter").setCause(pe).log();
            evaluateResult
                .exitCode(INVALID_PARAMETER_CODE)
                .errorMessage("Invalid parameter: " + pe.getMessage())
                .build();
        } catch (Throwable e) {
            log.atError().setMessage("Unexpected failure").setCause(e).log();
            evaluateResult
                .exitCode(UNEXPECTED_FAILURE_CODE)
                .errorMessage("Unexpected failure: " + e.getMessage())
                .build();
        }

        return evaluateResult.build();
    }
}
