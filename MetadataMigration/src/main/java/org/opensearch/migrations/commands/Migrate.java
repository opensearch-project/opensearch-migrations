package org.opensearch.migrations.commands;

import java.util.Collections;
import java.util.Map;

import org.opensearch.migrations.InferenceEndpointMigrator;
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

            // Migrate inference endpoints if source host is available
            Map<String, String> modelMappings = migrateInferenceEndpoints();

            var transformer = selectTransformer(clusters, modelMappings);
            migrateResult.transformations(transformer);

            var items = migrateAllItems(migrationMode, clusters, transformer.getTransformer(), context);
            migrateResult.items(items);
        } catch (ParameterException pe) {
            log.atError().setCause(pe).setMessage("Invalid parameter").log();
            migrateResult.exitCode(INVALID_PARAMETER_CODE)
                .errorMessage("Invalid parameter: " + pe.getMessage())
                .build();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected failure").log();
            migrateResult
                .exitCode(UNEXPECTED_FAILURE_CODE)
                .errorMessage(createUnexpectedErrorMessage(e))
                .build();
        }

        return migrateResult.build();
    }

    private Map<String, String> migrateInferenceEndpoints() {
        var sourceHost = arguments.sourceArgs.host;
        var targetHost = arguments.targetArgs.host;
        if (sourceHost == null || sourceHost.isEmpty()) {
            log.info("No source host provided, skipping inference endpoint migration");
            return Collections.emptyMap();
        }
        log.info("Migrating inference endpoints from {} to {}", sourceHost, targetHost);
        var migrator = new InferenceEndpointMigrator(sourceHost, targetHost);
        return migrator.migrateInferenceEndpoints();
    }
}
