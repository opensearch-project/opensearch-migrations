package org.opensearch.migrations.commands;


import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import com.beust.jcommander.ParameterException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Evaluate extends MigratorEvaluatorBase {

    public Evaluate(MigrateOrEvaluateArgs arguments) {
        super(arguments);
    }

    public EvaluateResult execute(RootMetadataMigrationContext context) {
        var migrationMode = MigrationMode.SIMULATE;
        var evaluateResult = EvaluateResult.builder();

        try {
            log.info("Running Metadata Evaluation");

            var clusters = createClusters();
            evaluateResult.clusters(clusters);

            var transformer = selectTransformer(clusters);
            evaluateResult.transformations(transformer);

            var items = migrateAllItems(migrationMode, clusters, transformer.getTransformer(), context);
            evaluateResult.items(items);
        } catch (ParameterException pe) {
            log.atError().setCause(pe).setMessage("Invalid parameter").log();
            evaluateResult
                .exitCode(INVALID_PARAMETER_CODE)
                .errorMessage("Invalid parameter: " + pe.getMessage())
                .build();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected failure").log();
            evaluateResult
                .exitCode(UNEXPECTED_FAILURE_CODE)
                .errorMessage(createUnexpectedErrorMessage(e))
                .build();
        }

        return evaluateResult.build();
    }
}
