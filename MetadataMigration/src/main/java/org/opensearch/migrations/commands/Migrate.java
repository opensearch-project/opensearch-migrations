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
        var migrateResult = MigrateResult.builder();

        try {
            log.info("Running Metadata Migration");

            var clusters = createClusters();
            migrateResult.clusters(clusters);

            var transformer = selectTransformer(clusters);

            var items = migrateAllItems(migrationMode, clusters, transformer, context);
            migrateResult.items(items);
        } catch (ParameterException pe) {
            log.atError().setCause(pe).setMessage("Invalid parameter").log();
            migrateResult.exitCode(INVALID_PARAMETER_CODE)
                .errorMessage("Invalid parameter: " + pe.getMessage())
                .build();
        } catch (Throwable e) {
            log.atError().setCause(e).setMessage("Unexpected failure").log();
            migrateResult
                .exitCode(UNEXPECTED_FAILURE_CODE)
                .errorMessage("Unexpected failure: " + e.getMessage())
                .build();
        }

        return migrateResult.build();
    }
}
