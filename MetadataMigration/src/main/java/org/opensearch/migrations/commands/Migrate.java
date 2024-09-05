package org.opensearch.migrations.commands;

import java.util.ArrayList;

import org.opensearch.migrations.MetadataArgs;
import org.opensearch.migrations.cli.ClusterReaderExtractor;
import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import com.beust.jcommander.ParameterException;
import com.rfs.transformers.TransformFunctions;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Migrate {

    static final int INVALID_PARAMETER_CODE = 999;
    static final int UNEXPECTED_FAILURE_CODE = 888;
    private final MetadataArgs arguments;
    private final ClusterReaderExtractor clusterReaderCliExtractor;

    public Migrate(MetadataArgs arguments) {
        this.arguments = arguments;
        clusterReaderCliExtractor = new ClusterReaderExtractor(arguments);
    }

    public MigrateResult execute(RootMetadataMigrationContext context) {
        var migrateResult = MigrateResult.builder();
        log.atInfo().setMessage("Command line arguments {0}").addArgument(arguments::toString).log();

        try {
            log.info("Running Metadata worker");

            var clusters = Clusters.builder();
            var sourceCluster = clusterReaderCliExtractor.extractClusterReader();
            clusters.source(sourceCluster);

            var targetCluster = ClusterProviderRegistry.getRemoteWriter(arguments.targetArgs.toConnectionContext(), arguments.dataFilterArgs);
            clusters.target(targetCluster);
            migrateResult.clusters(clusters.build());

            var transformer = TransformFunctions.getTransformer(
                sourceCluster.getVersion(),
                targetCluster.getVersion(),
                arguments.minNumberOfReplicas
            );

            log.info("Using transformation " + transformer.toString());

            var metadataResults = new MetadataRunner(
                arguments.snapshotName,
                sourceCluster.getGlobalMetadata(),
                targetCluster.getGlobalMetadataCreator(),
                transformer
            ).migrateMetadata(context.createMetadataMigrationContext());

            var items = Items.builder();
            var indexTemplates = new ArrayList<String>();
            indexTemplates.addAll(metadataResults.getLegacyTemplates());
            indexTemplates.addAll(metadataResults.getIndexTemplates());
            items.indexTemplates(indexTemplates);
            items.componentTemplates(metadataResults.getComponentTemplates());

            log.info("Metadata copy complete.");

            var indexes = new IndexRunner(
                arguments.snapshotName,
                sourceCluster.getIndexMetadata(),
                targetCluster.getIndexCreator(),
                transformer,
                arguments.dataFilterArgs.indexAllowlist
            ).migrateIndices(context.createIndexContext());
            items.indexes(indexes);
            migrateResult.items(items.build());
            log.info("Index copy complete.");
        } catch (ParameterException pe) {
            log.atError().setMessage("Invalid parameter").setCause(pe).log();
            migrateResult
                .exitCode(INVALID_PARAMETER_CODE)
                .errorMessage("Invalid parameter: " + pe.getMessage())
                .build();
        } catch (Throwable e) {
            log.atError().setMessage("Unexpected failure").setCause(e).log();
            migrateResult
                .exitCode(UNEXPECTED_FAILURE_CODE)
                .errorMessage("Unexpected failure: " + e.getMessage())
                .build();
        }

        return migrateResult.build();
    }
}
